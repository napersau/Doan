package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.dto.response.PetSummaryResponse;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.mapper.ClinicalMapper;
import com.vetclinic.clinical.repository.PetRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.SpeciesRepository;
import com.vetclinic.clinical.service.VisitAssembler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phần tra cứu thú cưng theo chủ nuôi cho appointment-service: appointments không còn cột owner_id (lược đồ v3.0), nên
 * "lịch hẹn của tôi" được lọc bằng pet_id IN (thú cưng của chủ nuôi).
 */
@ExtendWith(MockitoExtension.class)
class PetServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    @Mock
    private PetRepository petRepository;
    @Mock
    private SpeciesRepository speciesRepository;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private AuthClient authClient;
    @Mock
    private ClinicalMapper mapper;
    @Mock
    private VisitAssembler assembler;
    @Mock
    private PlatformTransactionManager transactionManager;

    private PetServiceImpl service() {
        return new PetServiceImpl(petRepository, speciesRepository, visitRepository, authClient, mapper, assembler,
                CLOCK, transactionManager);
    }

    @Test
    void getSummariesByOwner_returnsOnlyThatOwnersPets() {
        when(petRepository.findByOwnerId(8L)).thenReturn(List.of(
                Pet.builder().id(1L).ownerId(8L).name("Lu").build(),
                Pet.builder().id(2L).ownerId(8L).name("Milo").build()));

        List<PetSummaryResponse> result = service().getSummariesByOwner(8L);

        assertThat(result).extracting(PetSummaryResponse::id).containsExactly(1L, 2L);
        assertThat(result).extracting(PetSummaryResponse::ownerId).containsOnly(8L);
        assertThat(result).extracting(PetSummaryResponse::name).containsExactly("Lu", "Milo");
    }

    @Test
    void getSummariesByOwner_ownerWithoutPets_returnsEmptyList() {
        when(petRepository.findByOwnerId(9L)).thenReturn(List.of());

        assertThat(service().getSummariesByOwner(9L)).isEmpty();
    }

    @Test
    void getSummariesByOwner_nullOwner_doesNotQuery() {
        assertThat(service().getSummariesByOwner(null)).isEmpty();

        verify(petRepository, never()).findByOwnerId(org.mockito.ArgumentMatchers.any());
    }
}
