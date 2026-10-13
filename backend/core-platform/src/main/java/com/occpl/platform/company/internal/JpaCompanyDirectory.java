package com.occpl.platform.company.internal;

import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanySummary;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class JpaCompanyDirectory implements CompanyDirectory {

    private final CompanyRepository repository;

    JpaCompanyDirectory(CompanyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CompanySummary> find(UUID companyId) {
        return repository.findById(companyId).map(Company::toSummary);
    }

    @Override
    public Map<UUID, CompanySummary> findAll(Collection<UUID> companyIds) {
        if (companyIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(companyIds).stream()
                .map(Company::toSummary)
                .collect(Collectors.toMap(CompanySummary::id, Function.identity()));
    }
}
