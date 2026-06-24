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
}
