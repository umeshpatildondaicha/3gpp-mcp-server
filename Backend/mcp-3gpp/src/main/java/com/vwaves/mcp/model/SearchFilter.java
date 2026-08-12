package com.vwaves.mcp.model;

/**
 * The three optional retrieval filters that always travel together through
 * the search stack. Any component may be null or blank, meaning "no filter
 * on that dimension".
 */
public record SearchFilter(String series, String release, String docType) {

    /** No filtering on any dimension. */
    public static final SearchFilter NONE = new SearchFilter(null, null, null);

    /** Series-only filter, the common case on the procedure-flow path. */
    public static SearchFilter ofSeries(String series) {
        return new SearchFilter(series, null, null);
    }
}
