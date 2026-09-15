package io.github.ronaldobertolucci.mtgdeckbuilder.repository;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Deck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeckRepository extends JpaRepository<Deck, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Deck d where d.id = :id and d.userId = :userId")
    Optional<Deck> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") Long userId);

    Page<Deck> findByUserId(Long userId, Pageable pageable);
}
