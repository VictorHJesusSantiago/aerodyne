package io.aerodyne.navdata.arinc;

/**
 * Something wrong with one record.
 *
 * <p>A navdata file is thirty megabytes of third-party text and a handful of bad records is
 * normal. Rejecting the file over one unreadable NDB would ground the operation, so the parser
 * collects issues and carries on; the caller decides what severity it is prepared to accept.
 *
 * @param severity   how bad it is
 * @param lineNumber the line the problem is on, 1-based
 * @param recordType what the record was classified as
 * @param message    what went wrong
 * @param line       the offending line, so it can be shown in a report
 */
public record ArincParseIssue(Severity severity,
                              int lineNumber,
                              ArincRecordType recordType,
                              String message,
                              String line) {

    public enum Severity {
        /** The record was read, but something in it was odd. */
        WARNING,
        /** The record could not be read and was dropped. */
        ERROR
    }

    public static ArincParseIssue error(ArincRecord record, ArincRecordType type, String message) {
        return new ArincParseIssue(Severity.ERROR, record.lineNumber(), type, message, record.line());
    }

    public static ArincParseIssue warning(ArincRecord record, ArincRecordType type, String message) {
        return new ArincParseIssue(Severity.WARNING, record.lineNumber(), type, message, record.line());
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return severity + " line " + lineNumber + " [" + recordType.code() + "]: " + message;
    }
}
