package com.smartui.analysis.repository;

import com.smartui.analysis.model.Attribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AttributeRepository extends JpaRepository<Attribute, String> {
    List<Attribute> findByProjectId(String projectId);
    void deleteByProjectId(String projectId);
}
