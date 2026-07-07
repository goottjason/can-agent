package com.canagent.repository;

import com.canagent.domain.analysis.MonitorCheckLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MonitorCheckLogRepository extends JpaRepository<MonitorCheckLog, Long> {

    Optional<MonitorCheckLog> findTopByOrderByCheckTimeDesc();

    List<MonitorCheckLog> findTop30ByOrderByCheckTimeDesc();

    long countByCheckTimeBetween(LocalDateTime start, LocalDateTime end);
}
