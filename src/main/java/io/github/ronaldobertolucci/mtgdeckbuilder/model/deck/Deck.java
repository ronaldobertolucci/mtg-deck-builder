package io.github.ronaldobertolucci.mtgdeckbuilder.model.deck;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "decks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    @Setter
    private Long userId;

    @Column(nullable = false)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter
    private Format format;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeckStatus status = DeckStatus.UNDEFINED;

    private Instant analyzedAt;

    @ElementCollection
    @CollectionTable(name = "deck_analysis_messages", joinColumns = @JoinColumn(name = "deck_id"))
    @OrderColumn(name = "position")
    @Getter(AccessLevel.NONE)
    private List<AnalysisReason> analysisReasons = new ArrayList<>();

    public List<String> getAnalysisMessages() {
        return analysisReasons.stream().map(AnalysisReason::getMessage).toList();
    }

    public List<AnalysisReason> getAnalysisReasons() { return List.copyOf(analysisReasons); }

    public void recordAnalysis(DeckStatus status, Instant at, List<String> messages) {
        var severity = AnalysisReason.Severity.LEGACY;
        recordStructuredAnalysis(status, at, messages.stream()
                .map(message -> new AnalysisReason("LEGACY_MESSAGE", severity, message, java.util.Map.of())).toList());
    }

    public void recordStructuredAnalysis(DeckStatus status, Instant at, List<AnalysisReason> reasons) {
        this.status = Objects.requireNonNull(status);
        this.analyzedAt = Objects.requireNonNull(at);
        this.analysisReasons.clear();
        this.analysisReasons.addAll(reasons);
    }

    public void invalidateAnalysis() {
        status = DeckStatus.UNDEFINED;
        analyzedAt = null;
        analysisReasons.clear();
    }

    @OneToMany(mappedBy = "deck", cascade = CascadeType.ALL, orphanRemoval = true)
    @Getter(AccessLevel.NONE)
    private List<DeckCard> cards = new ArrayList<>();

    public Deck(Long userId, String name, Format format) {
        this.userId = userId;
        this.name = name;
        this.format = format;
    }

    public List<DeckCard> getCards() {
        return Collections.unmodifiableList(cards);
    }

    public void addCard(DeckCard card) {
        Objects.requireNonNull(card, "card must not be null");
        if (card.getDeck() != null && card.getDeck() != this) {
            throw new IllegalArgumentException("Card already belongs to another deck");
        }
        if (!cards.contains(card)) {
            cards.add(card);
            card.setDeck(this);
        }
    }

    public void removeCard(DeckCard card) {
        if (cards.remove(card)) {
            card.setDeck(null);
        }
    }
}
