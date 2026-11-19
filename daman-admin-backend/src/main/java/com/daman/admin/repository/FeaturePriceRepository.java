package com.daman.admin.repository;

import com.daman.admin.entity.FeaturePrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeaturePriceRepository extends JpaRepository<FeaturePrice, String> {

    List<FeaturePrice> findAllByOrderBySortOrderAsc();
}
