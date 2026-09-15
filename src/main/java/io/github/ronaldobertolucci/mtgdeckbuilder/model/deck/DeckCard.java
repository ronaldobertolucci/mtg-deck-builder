package io.github.ronaldobertolucci.mtgdeckbuilder.model.deck;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "deck_cards", uniqueConstraints = @UniqueConstraint(
        name = "uk_deck_cards_deck_oracle_board", columnNames = {"deck_id", "oracle_id", "board_type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeckCard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deck_id", nullable = false)
    @Setter(AccessLevel.PACKAGE)
    private Deck deck;

    @Column(name = "oracle_id", nullable = false)
    @Setter
    private UUID oracleId;

    @Column(nullable = false)
    @Setter
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "board_type", nullable = false, length = 20)
    @Setter
    private BoardType boardType;

    public DeckCard(UUID oracleId, int quantity, BoardType boardType) {
        this.oracleId = oracleId;
        this.quantity = quantity;
        this.boardType = boardType;
    }
}
