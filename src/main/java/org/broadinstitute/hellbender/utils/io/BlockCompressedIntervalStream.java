package org.broadinstitute.hellbender.utils.io;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.seekablestream.SeekableBufferedStream;
import htsjdk.samtools.seekablestream.SeekablePathStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.BlockCompressedInputStream;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.samtools.util.BlockCompressedStreamConstants;
import htsjdk.tribble.CloseableTribbleIterator;
import htsjdk.tribble.FeatureCodec;
import htsjdk.tribble.FeatureReader;
import org.broadinstitute.hellbender.engine.GATKPath;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.CollatingInterval;
import org.broadinstitute.hellbender.utils.collections.IntervalTree;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

public class BlockCompressedIntervalStream {

    // our final empty block adds to the extra information
    // it has a virtual file pointer to the start of the index
    public static final byte[] EMPTY_GZIP_BLOCK_WITH_INDEX_POINTER = {
            BlockCompressedStreamConstants.GZIP_ID1,
            (byte)BlockCompressedStreamConstants.GZIP_ID2,
            BlockCompressedStreamConstants.GZIP_CM_DEFLATE,
            BlockCompressedStreamConstants.GZIP_FLG,
            0, 0, 0, 0, // modification time
            BlockCompressedStreamConstants.GZIP_XFL,
            (byte)BlockCompressedStreamConstants.GZIP_OS_UNKNOWN,
            BlockCompressedStreamConstants.GZIP_XLEN + 12, 0,
            BlockCompressedStreamConstants.BGZF_ID1,
            BlockCompressedStreamConstants.BGZF_ID2,
            BlockCompressedStreamConstants.BGZF_LEN, 0,
            39, 0, // Total block size - 1 as little-endian short
            (byte)'I', (byte)'P', 8, 0, // index pointer extra data

            // 8-byte little-endian long representing file-pointer to beginning of index
            // (this data gets overwritten each time a stream is closed)
            1, 2, 3, 4, 5, 6, 7, 8,

            3, 0, // empty payload
            0, 0, 0, 0, // crc
            0, 0, 0, 0, // uncompressedSize
    };
    public static final int FILE_POINTER_OFFSET = 22;

    public static final String BCI_FILE_EXTENSION = ".bci";

    // each compressed block of data will have (at least) one of these as a part of the index
    // for each contig that appears in a compressed block the CollatingInterval tracks the smallest
    //   starting coordinate and largest end coordinate of any object in the block
    // the filePosition member is the virtual file offset of the first object in the block (or, the
    //   first object for a new contig, if there are multiple contigs represented within the block)
    public final static class IndexEntry {
        final CollatingInterval interval;
        long filePosition;

        public IndexEntry( final CollatingInterval interval, final long filePosition ) {
            this.interval = interval;
            this.filePosition = filePosition;
        }

        public IndexEntry( final DataInputStream dis,
                           final SAMSequenceDictionary dict ) throws IOException {
            final int contigId = dis.readInt();
            final int start = dis.readInt();
            final int end = dis.readInt();
            this.interval = new CollatingInterval(dict.getSequence(contigId), start, end);
            this.filePosition = dis.readLong();
        }

        public CollatingInterval getInterval() { return interval; }
        public long getFilePosition() { return filePosition; }

        public void write( final DataOutputStream dos ) throws IOException {
            interval.write(dos);
            dos.writeLong(filePosition);
        }
    }

    @FunctionalInterface
    public interface WriteFunc<T extends CollatingInterval> {
        T write( T tee, DataOutputStream dos ) throws IOException;
    }

    // a class for writing arbitrary objects to a block compressed stream with a self-contained index
    // the only restriction is that you must supply a lambda that writes enough state to a DataOutputStream
    //   to allow you to reconstitute the object when you read it back in later AND you have to
    //   return an CollatingInterval so that we can do indexing.
    public static class Writer <T extends CollatingInterval> {
        final GATKPath path;
        final SAMSequenceDictionary dict;
        final WriteFunc<T> writeFunc;
        final OutputStream os;
        final BlockCompressedOutputStream bcos;
        final DataOutputStream dos;
        CollatingInterval lastInterval;
        final List<IndexEntry> indexEntries;
        long blockFilePosition;
        String blockContig;
        int blockStart;
        int blockEnd;
        boolean firstBlockMember;

        public final static int DEFAULT_COMPRESSION_LEVEL = 6;

        public Writer( final GATKPath path,
                       final SAMSequenceDictionary dict,
                       final Class<T> tClass,
                       final String version,
                       final WriteFunc<T> writeFunc ) {
            this(path, dict, tClass, version, writeFunc, DEFAULT_COMPRESSION_LEVEL);
        }

        public Writer( final GATKPath path,
                       final SAMSequenceDictionary dict,
                       final Class<T> tClass,
                       final String version,
                       final WriteFunc<T> writeFunc,
                       final int compressionLevel ) {
            this.path = path;
            this.dict = dict;
            this.writeFunc = writeFunc;
            this.os = path.getOutputStream();
            this.bcos = new BlockCompressedOutputStream(os, (Path)null, compressionLevel);
            this.dos = new DataOutputStream(bcos);
            this.lastInterval = null;
            this.indexEntries = new ArrayList<>();
            this.firstBlockMember = true;
            writeClassAndVersion(tClass, version);
            writeDictionary();
        }

        private void writeClassAndVersion( final Class<T> tClass, final String version ) {
            try {
                dos.writeUTF(tClass.getSimpleName());
                dos.writeUTF(version);
            } catch ( final IOException ioe ) {
                throw new UserException("can't write class and version to " + path, ioe);
            }
        }
        private void writeDictionary() {
            try {
                dos.writeInt(dict.size());
                for ( final SAMSequenceRecord rec : dict.getSequences() ) {
                    dos.writeInt(rec.getSequenceLength());
                    dos.writeUTF(rec.getSequenceName());
                }
                dos.flush();
            } catch ( final IOException ioe ) {
                throw new UserException("can't write dictionary to " + path, ioe);
            }
        }

        public void write( final T tee ) {
            final long prevFilePosition = bcos.getPosition();
            // write the object, and make sure the order is OK (coordinate-sorted intervals)
            final CollatingInterval interval;
            try {
                interval = writeFunc.write(tee, dos);
            } catch ( final IOException ioe ) {
                throw new UserException("can't write to " + path, ioe);
            }
            if ( lastInterval != null && interval.compareTo(lastInterval) < 0 ) {
                throw new UserException("intervals are not coordinate sorted");
            }

            // if this is the first interval we've seen in a block, just capture the block-start data
            if ( firstBlockMember || lastInterval == null ) {
                startBlock(prevFilePosition, interval);
                return;
            }

            // if the contig changes emit a new index entry (for the previous contig) and
            //   restart tracking of the block
            if ( !interval.contigsMatch(lastInterval) ) {
                addIndexEntry();
                startBlock(prevFilePosition, interval);
                return;
            }

            // extend the tracked interval, as necessary
            blockEnd = Math.max(blockEnd, interval.getEnd());
            lastInterval = interval;

            // if writing this element caused a new block to be compressed and added to the file
            if ( isNewBlock(prevFilePosition, bcos.getPosition()) ) {
                addIndexEntry();
                firstBlockMember = true;
            }
        }

        public void close() {
            // take care of any pending index entry, if necessary
            if ( !firstBlockMember ) {
                addIndexEntry();
            }

            try {
                dos.flush(); // complete the data block

                long indexPosition = bcos.getPosition(); // current position is the start of the index

                // write the index entries
                dos.writeInt(indexEntries.size());
                for ( final IndexEntry indexEntry : indexEntries ) {
                    indexEntry.write(dos);
                }
                dos.flush(); // and complete the block

                // write a 0-length terminator block at the end that captures the index position
                final byte[] emptyBlockWithIndexPointer =
                        Arrays.copyOf(EMPTY_GZIP_BLOCK_WITH_INDEX_POINTER,
                                EMPTY_GZIP_BLOCK_WITH_INDEX_POINTER.length);
                for ( int idx = FILE_POINTER_OFFSET; idx != FILE_POINTER_OFFSET + 8; ++idx ) {
                    emptyBlockWithIndexPointer[idx] = (byte)indexPosition;
                    indexPosition >>>= 8;
                }
                os.write(emptyBlockWithIndexPointer);

                bcos.close(false); // we've already handled the terminator block
            } catch ( final IOException ioe ) {
                throw new UserException("unable to add index and close " + path, ioe);
            }
        }

        private void startBlock( final long filePosition, final CollatingInterval interval ) {
            blockFilePosition = filePosition;
            lastInterval = interval;
            blockContig = interval.getContig();
            blockStart = interval.getStart();
            blockEnd = interval.getEnd();
            firstBlockMember = false;
        }

        private void addIndexEntry() {
            final CollatingInterval blockInterval =
                    new CollatingInterval(dict, blockContig, blockStart, blockEnd);
            indexEntries.add(new IndexEntry(blockInterval, blockFilePosition));
        }
    }

    // a class for reading arbitrary objects from a block compressed stream with a self-contained index
    // the only restriction is that you must supply a lambda that reads from a DataInputStream
    //   to reconstitute the object.
    public static final class Reader <T extends CollatingInterval> implements FeatureReader<T> {
        final Path path;
        final FeatureCodec<T, Reader<T>> codec;
        final long indexFilePointer;
        final BlockCompressedInputStream bcis;
        final DataInputStream dis;
        final String className;
        final String version;
        final SAMSequenceDictionary dict;
        final long dataFilePointer;
        IntervalTree<Long> index;

        public Reader( final Path path, final FeatureCodec<T, Reader<T>> codec ) {
            this.path = path;
            this.codec = codec;
            final SeekablePathStream sps;
            try {
                sps = new SeekablePathStream(path);
            } catch ( final IOException ioe ) {
                throw new UserException("unable to open " + path, ioe);
            }
            this.indexFilePointer = findIndexFilePointer(sps);
            this.bcis = new BlockCompressedInputStream(new SeekableBufferedStream(sps));
            this.dis = new DataInputStream(bcis);
            try {
                this.className = dis.readUTF();
                this.version = dis.readUTF();
            } catch ( final IOException ioe ) {
                throw new UserException("can't read class and version from " + path, ioe);
            }
            final String expectedClassName = codec.getFeatureType().getSimpleName();
            if ( !className.equals(expectedClassName) ) {
                throw new UserException("can't use " + path + " to read " + expectedClassName +
                        " features -- it contains " + className + " features");
            }
            this.dict = readDictionary(dis);
            this.dataFilePointer = bcis.getPosition(); // having read dictionary, we're pointing at the data
        }

        public Reader( final Reader<T> reader ) {
            this.path = reader.path;
            this.codec = reader.codec;
            this.indexFilePointer = reader.indexFilePointer;
            try {
                this.bcis = new BlockCompressedInputStream(
                        new SeekableBufferedStream(new SeekablePathStream(path)));
            } catch ( final IOException ioe ) {
                throw new UserException("unable to clone stream for " + path, ioe);
            }
            this.dis = new DataInputStream(bcis);
            this.className = reader.className;
            this.version = reader.version;
            this.dict = reader.dict;
            this.dataFilePointer = reader.dataFilePointer;
            this.index = reader.index;
        }

        public SAMSequenceDictionary getDictionary() { return dict; }
        public DataInputStream getStream() { return dis; }
        public String getVersion() { return version; }

        public boolean hasNext() {
            final long position = bcis.getPosition();
            // A BlockCompressedInputStream returns a 0 position when closed
            return position > 0 && position < indexFilePointer;
        }

        @Override
        public CloseableTribbleIterator<T> query( final String chr, final int start, final int end )
                throws IOException {
            if ( index == null ) {
                loadIndex(bcis);
                close();
            }
            final CollatingInterval interval = new CollatingInterval(dict, chr, start, end);
            return new OverlapIterator<>(interval, new Reader<>(this));
        }

        @Override public CloseableTribbleIterator<T> iterator() {
            return new CompleteIterator<>(new Reader<>(this));
        }

        @Override public void close() {
            try {
                dis.close();
            } catch ( final IOException ioe ) {
                throw new UserException("unable to close " + path, ioe);
            }
        }

        @Override public List<String> getSequenceNames() {
            final List<String> names = new ArrayList<>(dict.size());
            for ( final SAMSequenceRecord rec : dict.getSequences() ) {
                names.add(rec.getSequenceName());
            }
            return names;
        }

        @Override public Object getHeader() { return dict; }

        @Override public boolean isQueryable() { return true; }

        public void seekStream( final long filePointer ) {
            try {
                bcis.seek(filePointer);
            } catch ( final IOException ioe ) {
                throw new UserException("unable to position stream for " + path, ioe);
            }
        }

        public T readStream() {
            try {
                return codec.decode(this);
            } catch ( final IOException ioe ) {
                throw new GATKException("can't read " + path, ioe);
            }
        }

        private long findIndexFilePointer( final SeekableStream ss ) {
            final int finalBlockLen = EMPTY_GZIP_BLOCK_WITH_INDEX_POINTER.length;
            final byte[] finalBlock = new byte[finalBlockLen];
            try {
                ss.seek(ss.length() - finalBlockLen);
                ss.readFully(finalBlock);
                ss.seek(0);
            } catch ( final IOException ioe ) {
                throw new UserException("unable to read final bgzip block from " + path, ioe);
            }
            for ( int idx = 0; idx != FILE_POINTER_OFFSET; ++idx ) {
                if ( EMPTY_GZIP_BLOCK_WITH_INDEX_POINTER[idx] != finalBlock[idx] ) {
                    throw new UserException(
                            "unable to recover index pointer from final block of " + path);
                }
            }
            for ( int idx = FILE_POINTER_OFFSET + 8; idx != finalBlockLen; ++idx ) {
                if ( EMPTY_GZIP_BLOCK_WITH_INDEX_POINTER[idx] != finalBlock[idx] ) {
                    throw new UserException(
                            "unable to recover index pointer from final block of " + path);
                }
            }
            long indexFilePointer = 0;
            int idx = FILE_POINTER_OFFSET + 8;
            while ( --idx >= FILE_POINTER_OFFSET ) {
                indexFilePointer <<= 8;
                indexFilePointer |= finalBlock[idx] & 0xFFL;
            }
            return indexFilePointer;
        }

        private SAMSequenceDictionary readDictionary( final DataInputStream dis ) {
            try {
                final int nRecs = dis.readInt();
                final List<SAMSequenceRecord> seqRecs = new ArrayList<>(nRecs);
                for ( int idx = 0; idx != nRecs; ++idx ) {
                    final int contigSize = dis.readInt();
                    final String contigName = dis.readUTF();
                    seqRecs.add(new SAMSequenceRecord(contigName, contigSize));
                }
                return new SAMSequenceDictionary(seqRecs);
            } catch ( final IOException ioe ) {
                throw new UserException("unable to read dictionary from " + path, ioe);
            }
        }

        private void loadIndex( final BlockCompressedInputStream bcis ) {
            final IntervalTree<Long> intervalTree = new IntervalTree<>();
            try {
                bcis.seek(indexFilePointer);
                final DataInputStream dis = new DataInputStream(bcis);
                int nEntries = dis.readInt();
                while ( nEntries-- > 0 ) {
                    final IndexEntry entry = new IndexEntry(dis, dict);
                    intervalTree.put(entry.getInterval(), entry.getFilePosition());
                }
                bcis.seek(dataFilePointer);
            } catch ( final IOException ioe ) {
                throw new UserException("unable to read index from " + path, ioe);
            }
            index = intervalTree;
        }

        private static class CompleteIterator <T extends CollatingInterval>
                implements CloseableTribbleIterator<T> {
            final Reader<T> reader;
            public CompleteIterator( final Reader<T> reader ) {
                this.reader = reader;
                reader.seekStream(reader.dataFilePointer);
            }

            @Override public Iterator<T> iterator() {
                return new CompleteIterator<>(new Reader<>(reader));
            }

            @Override public boolean hasNext() {
                return reader.hasNext();
            }

            @Override public T next() {
                if ( !hasNext() ) {
                    throw new NoSuchElementException("feature iterator has no next element");
                }
                return reader.readStream();
            }

            @Override public void close() { reader.close(); }
        }
        // find all the objects in the stream inflating just those blocks that might have relevant objects
        private static class OverlapIterator <T extends CollatingInterval>
                implements CloseableTribbleIterator<T> {
            final CollatingInterval interval;
            final Reader<T> reader;
            final Iterator<IntervalTree.Entry<Long>> indexEntryIterator;
            long blockStartPosition;
            T nextT;

            public OverlapIterator( final CollatingInterval interval, final Reader<T> reader ) {
                this.interval = interval;
                this.reader = reader;
                this.indexEntryIterator = reader.index.overlappers(interval);
                this.blockStartPosition = -1;
                advance();
            }

            @Override public boolean hasNext() { return nextT != null; }

            @Override public T next() {
                final T result = nextT;
                if ( result == null ) {
                    throw new NoSuchElementException("overlapper iterator has no next element");
                }
                advance();
                return result; }

            @Override public void close() { reader.close(); nextT = null; }

            @Override public CloseableTribbleIterator<T> iterator() {
                return new OverlapIterator<>(interval, new Reader<>(reader));
            }

            private void advance() {
                do {
                    if ( isNewBlock(blockStartPosition, reader.bcis.getPosition()) ) {
                        if ( !indexEntryIterator.hasNext() ) {
                            nextT = null;
                            return;
                        }
                        blockStartPosition = indexEntryIterator.next().getValue();
                        reader.seekStream(blockStartPosition);
                    }
                    nextT = reader.readStream();
                    if ( interval.isUpstreamOf(nextT) ) {
                        nextT = null;
                        return;
                    }
                } while ( !interval.overlaps(nextT) );
            }
        }
    }

    public static boolean isNewBlock( final long filePosition1, final long filePosition2 ) {
        // upper 48 bits contain the block offset
        // check to see if there are any bit differences in those upper 48 bits
        return ((filePosition1 ^ filePosition2) & ~0xffffL) != 0;
    }
}
