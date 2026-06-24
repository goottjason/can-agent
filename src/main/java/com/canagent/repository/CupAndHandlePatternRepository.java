package com.canagent.repository;

import com.canagent.domain.analysis.CupAndHandlePattern;
import com.canagent.domain.analysis.PatternStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CupAndHandlePatternRepository extends JpaRepository<CupAndHandlePattern, Long> {

    List<CupAndHandlePattern> findByStockIdAndStatusOrderByCupStartDateDesc(
            Long stockId, PatternStatus status);

    List<CupAndHandlePattern> findByStatus(PatternStatus status);
}
