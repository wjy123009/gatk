package org.broadinstitute.hellbender.utils.codecs;

import htsjdk.samtools.util.LocationAware;
import htsjdk.tribble.Feature;
import htsjdk.tribble.FeatureCodec;
import htsjdk.tribble.FeatureCodecHeader;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.tools.walkers.sv.PairedEndAndSplitReadEvidenceCollection.LocusDepth;
import org.broadinstitute.hellbender.utils.io.BlockCompressedIntervalStream.Reader;

import java.io.IOException;
import java.io.InputStream;

import static htsjdk.tribble.FeatureCodecHeader.EMPTY_HEADER;
import static org.broadinstitute.hellbender.utils.io.BlockCompressedIntervalStream.BCI_FILE_EXTENSION;

public class LocusDepthCodec implements FeatureCodec<LocusDepth, Reader<LocusDepth>> {
    @Override
    public Feature decodeLoc( final Reader<LocusDepth> reader ) throws IOException {
        return decode(reader);
    }

    @Override
    public LocusDepth decode( final Reader<LocusDepth> reader ) throws IOException {
        return new LocusDepth(reader.getDictionary(), reader.getStream());
    }

    @Override
    public FeatureCodecHeader readHeader( final Reader<LocusDepth> reader ) throws IOException {
        return EMPTY_HEADER;
    }

    @Override
    public Class<LocusDepth> getFeatureType() { return LocusDepth.class; }

    @Override
    public Reader<LocusDepth> makeSourceFromStream( final InputStream is ) {
        throw new GATKException("wasn't expecting to execute this code path");
    }

    @Override
    public LocationAware makeIndexableSourceFromStream( final InputStream is ) {
        throw new GATKException("wasn't expecting to execute this code path");
    }

    @Override
    public boolean isDone( final Reader<LocusDepth> reader ) { return !reader.hasNext(); }

    @Override
    public void close( final Reader<LocusDepth> reader ) { reader.close(); }

    @Override
    public boolean canDecode( final String path ) { return path.endsWith(BCI_FILE_EXTENSION); }
}
