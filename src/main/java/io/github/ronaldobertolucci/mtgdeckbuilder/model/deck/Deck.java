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
