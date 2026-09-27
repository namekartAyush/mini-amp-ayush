package com.namekart.auction_api.user.repository;

import com.namekart.auction_api.user.model.ShortlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShortlistItemRepository extends JpaRepository<ShortlistItem, Long> {
    List<ShortlistItem> findByUserId(Long userId);
    List<ShortlistItem> findByUserIdOrderByPriorityAsc(Long userId);
}
