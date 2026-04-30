package com.nebula.api.util;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A Pageable implementation that uses absolute offset instead of page-based indexing.
 * Spring Data's PageRequest computes offset as page * size, which only works
 * when offset is a multiple of limit. This class supports arbitrary offsets.
 */
public class OffsetPageable implements Pageable {

    private final int offset;
    private final int limit;

    public OffsetPageable(int offset, int limit) {
        this.offset = Math.max(offset, 0);
        this.limit = Math.max(limit, 1);
    }

    @Override public int getPageNumber() { return offset / limit; }
    @Override public int getPageSize() { return limit; }
    @Override public long getOffset() { return offset; }
    @Override public Sort getSort() { return Sort.unsorted(); }
    @Override public Pageable next() { return new OffsetPageable(offset + limit, limit); }
    @Override public Pageable previousOrFirst() { return offset > limit ? new OffsetPageable(offset - limit, limit) : first(); }
    @Override public Pageable first() { return new OffsetPageable(0, limit); }
    @Override public Pageable withPage(int pageNumber) { return new OffsetPageable(pageNumber * limit, limit); }
    @Override public boolean hasPrevious() { return offset > 0; }
}
