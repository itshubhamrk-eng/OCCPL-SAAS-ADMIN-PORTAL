package com.occpl.platform.company.api;

import java.util.UUID;

public record CompanySummary(UUID id, String code, String displayName, CompanyStatus status) {
}
