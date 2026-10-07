package io.github.ronaldobertolucci.mtgdeckbuilder.model.deck;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;
import java.util.Objects;

/** A stable code and JSON parameters accompany the human-readable English message. */
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
@ToString
public class AnalysisReason {
    public enum Severity { VIOLATION, UNCERTAINTY, LEGACY }

    @Column(nullable = false, length = 100)
    private String code;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;
    @Column(nullable = false, length = 2000)
    private String message;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private Map<String, Object> parameters;

    public AnalysisReason(String code, Severity severity, String message, Map<String, Object> parameters) {
        this.code = Objects.requireNonNull(code);
        this.severity = Objects.requireNonNull(severity);
        this.message = Objects.requireNonNull(message);
        this.parameters = Map.copyOf(parameters);
    }

    public String getCode() { return code; }
    public Severity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public Map<String, Object> getParameters() { return Map.copyOf(parameters); }
}
