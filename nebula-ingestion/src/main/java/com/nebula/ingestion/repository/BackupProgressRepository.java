package com.nebula.ingestion.repository;

import com.nebula.common.entity.BackupProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupProgressRepository extends JpaRepository<BackupProgress, String> {
}
