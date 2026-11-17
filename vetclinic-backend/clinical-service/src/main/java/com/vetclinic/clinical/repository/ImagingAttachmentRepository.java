package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.ImagingAttachment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImagingAttachmentRepository extends JpaRepository<ImagingAttachment, Long> {
    List<ImagingAttachment> findByImagingResultIdIn(Collection<Long> imagingResultIds);
}
