package com.bscllc.learning.citibike.ingestion;

/** Diagnostics are fixed codes and action hints, never SQL, paths, or source rows. */
public final class IngestionFailure extends RuntimeException {
    private final String code;
    public IngestionFailure(String code) { super(code); this.code=code; }
    public String code() { return code; }
    public static String diagnostic(String code) {
        return switch(code) {
            case "unsupported_schema" -> "Use the current 13-column Citi Bike schema; legacy exports require an explicit conversion.";
            case "invalid_data" -> "Review aggregate profile counts for required fields, timestamps, categories, durations, and coordinates.";
            case "duplicate_rides" -> "Resolve duplicate ride IDs within this month or against another active month/city.";
            case "missing_publication", "publication_overdue" -> "The requested complete month is not published; retry after publication.";
            case "unsafe_archive" -> "The archive contains an unsupported or unsafe ZIP entry/layout.";
            case "quota", "deadline" -> "Review configured disk, archive, extraction, and job resource limits.";
            default -> "Review ingestion status and retry after resolving the reported stage failure.";
        };
    }
}
