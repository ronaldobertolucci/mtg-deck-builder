package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

import java.util.List;
import java.util.UUID;

public class RuleViolationException extends RuntimeException {
    private final RuleErrorCode code;
    private final String field;
    private final List<UUID> oracleIds;
    private final Integer line;

    public RuleViolationException(String message) {
        this(null, message, null, List.of());
    }

    public RuleViolationException(RuleErrorCode code, String message, String field, List<UUID> oracleIds) {
        this(code, message, field, oracleIds, null);
    }

    public RuleViolationException(RuleErrorCode code, String message, String field, List<UUID> oracleIds,
                                  Integer line) {
        super(message);
        this.code = code;
        this.field = field;
        this.oracleIds = oracleIds == null ? List.of() : oracleIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        this.line = line;
    }

    public RuleErrorCode getCode() { return code; }
    public String getField() { return field; }
    public List<UUID> getOracleIds() { return oracleIds; }
    public Integer getLine() { return line; }
}
