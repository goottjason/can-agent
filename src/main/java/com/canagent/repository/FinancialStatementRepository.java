package com.canagent.repository;

import com.canagent.domain.stock.FinancialStatement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FinancialStatementRepository extends JpaRepository<FinancialStatement, Long> {

    List<FinancialStatement> findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(Long stockId);

    Optional<FinancialStatement> findByStockIdAndFiscalYearAndFiscalQuarter(
            Long stockId, Integer fiscalYear, Integer fiscalQuarter);

    // P4: 연간행(ANNUAL, fiscalQuarter=null) upsert 조회.
    // 파생 쿼리에 null을 넘기면 `= NULL`(항상 false)이 되어 중복 삽입되므로 IS NULL 전용 finder를 둔다.
    Optional<FinancialStatement> findByStockIdAndFiscalYearAndFiscalQuarterIsNull(
            Long stockId, Integer fiscalYear);
}
