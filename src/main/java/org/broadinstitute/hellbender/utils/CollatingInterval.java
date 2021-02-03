package org.broadinstitute.hellbender.utils;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.util.Locatable;
import htsjdk.tribble.Feature;
import org.broadinstitute.hellbender.exceptions.GATKException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class CollatingInterval implements Feature, Comparable<CollatingInterval> {
    private final SAMSequenceRecord contig;
    private final int start;
    private final int end;

    public CollatingInterval( final SAMSequenceDictionary dict, final String contigName,
                              final int start, final int end ) {
        this(Utils.nonNull(dict.getSequence(contigName), () -> contigName + " not in dictionary"),
                start, end);
    }

    public CollatingInterval( final SAMSequenceRecord contig, final int start, final int end ) {
        if ( contig == null ) {
            throw new GATKException("null contig supplied");
        }
        final int sequenceLength = contig.getSequenceLength();
        if ( start < 1 || start > sequenceLength ) {
            throw new GATKException("starting coordinate " + start + " is not within contig bounds");
        }
        if ( end < start || end > sequenceLength ) {
            throw new GATKException("ending coordinate " + end +
                    " is less than start or greater than contig length");
        }
        this.contig = contig;
        this.start = start;
        this.end = end;
    }

    public CollatingInterval( final SAMSequenceDictionary dict, final Locatable loc ) {
        this.contig = dict.getSequence(loc.getContig());
        this.start = loc.getStart();
        this.end = loc.getEnd();
    }

    public CollatingInterval( final SAMSequenceDictionary dict,
                              final DataInputStream dis ) throws IOException {
        this(dict.getSequence(dis.readInt()), dis.readInt(), dis.readInt());
    }

    @Override public String getContig() { return contig.getSequenceName(); }
    @Override public int getStart() { return start; }
    @Override public int getEnd() { return end; }
    @Override public boolean overlaps( final Locatable that ) {
        return contigsMatch(that) && start <= that.getEnd() && that.getStart() <= end;
    }
    @Override public boolean contains( Locatable that ) {
        return contigsMatch(that) && that.getStart() >= start && that.getEnd() <= end;
    }
    @Override public boolean contigsMatch( final Locatable that ) {
        return contig.getSequenceName().equals(that.getContig());
    }

    public boolean overlaps( final CollatingInterval that ) {
        return contigsMatch(that) && this.start <= that.end && that.start <= this.end;
    }
    public boolean contains( final CollatingInterval that ) {
        return contigsMatch(that) && that.start >= this.start && that.end <= this.end;
    }
    public boolean contigsMatch( final CollatingInterval that ) {
        return this.contig == that.contig ||
                this.contig.getSequenceIndex() == that.contig.getSequenceIndex();
    }

    @Override public int compareTo( final CollatingInterval that ) {
        int result = Integer.compare(this.contig.getSequenceIndex(), that.contig.getSequenceIndex());
        if ( result == 0 ) {
            result = Integer.compare(this.start, that.start);
            if ( result == 0 ) {
                result = Integer.compare(this.end, that.end);
            }
        }
        return result;
    }

    @Override public boolean equals( final Object obj ) {
        if ( this == obj ) return true;
        if ( !(obj instanceof CollatingInterval) ) return false;
        final CollatingInterval that = (CollatingInterval)obj;
        return contigsMatch(that) && this.start == that.start && this.end == that.end;
    }

    @Override public int hashCode() {
        return 241*(241*(241*contig.getSequenceIndex() + start) + end);
    }

    @Override public String toString() {
        return contig.getSequenceName() + ":" + start + "-" + end;
    }

    public boolean isUpstreamOf( final CollatingInterval that ) {
        final int thisContigId = contig.getSequenceIndex();
        final int thatContigId = that.contig.getSequenceIndex();
        if ( thisContigId < thatContigId ) {
            return true;
        }
        if ( thisContigId == thatContigId ) {
            if ( end < that.getStart() ) {
                return true;
            }
        }
        return false;
    }

    public static CollatingInterval laterEnding( final CollatingInterval interval1,
                                                 final CollatingInterval interval2 ) {
        final int contigId1 = interval1.contig.getSequenceIndex();
        final int contigId2 = interval2.contig.getSequenceIndex();
        if ( contigId1 == contigId2 ) {
            return interval1.end > interval2.end ? interval1 : interval2;
        }
        if ( contigId1 > contigId2 ) {
            return interval1;
        }
        return interval2;
    }

    public CollatingInterval write( final DataOutputStream dos ) throws IOException {
        dos.writeInt(contig.getSequenceIndex());
        dos.writeInt(start);
        dos.writeInt(end);
        return this;
    }
}
