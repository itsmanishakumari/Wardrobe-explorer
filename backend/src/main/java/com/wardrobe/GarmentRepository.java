package com.wardrobe;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GarmentRepository extends JpaRepository<Garment, Long> {
    List<Garment> findAllByOrderByCreatedAtDescIdDesc();
}