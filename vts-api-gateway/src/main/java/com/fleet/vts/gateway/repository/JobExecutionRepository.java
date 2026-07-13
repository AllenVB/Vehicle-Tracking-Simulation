package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.JobExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {
}
