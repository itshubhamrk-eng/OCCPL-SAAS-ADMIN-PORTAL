package com.occpl.sample;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The product's own feature. Every request is authorised by Core (membership, permission, company
 * status, entitlement) for the company in the path; the product never trusts the token alone.
 * Creating a note consumes one unit of the {@code notes} limit; deleting gives it back.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/notes")
class NoteController {

    static final String FEATURE = "notes";
    private static final Logger log = LoggerFactory.getLogger(NoteController.class);

    private final TenantRepository tenants;
    private final NoteRepository notes;
    private final CoreClient core;

    NoteController(TenantRepository tenants, NoteRepository notes, CoreClient core) {
        this.tenants = tenants;
        this.notes = notes;
        this.core = core;
    }

    record NewNote(@NotBlank @Size(max = 200) String title, @Size(max = 4000) String body) {
    }

    @GetMapping
    List<NoteRepository.Note> list(@PathVariable UUID companyId, @AuthenticationPrincipal Jwt user) {
        authorize(companyId, user, null);
        return notes.list(companyId);
    }

    @PostMapping
    ResponseEntity<NoteRepository.Note> create(@PathVariable UUID companyId, @AuthenticationPrincipal Jwt user,
            @Valid @RequestBody NewNote request) {
        authorize(companyId, user, FEATURE);
        UUID id = UUID.randomUUID();
        try {
            core.consume(companyId, FEATURE, 1, "note-" + id);
        } catch (CoreClient.UsageRejected e) {
            throw new ResponseStatusException(HttpStatus.valueOf(e.status()), "Note limit: " + e.code());
        }
        NoteRepository.Note note = new NoteRepository.Note(id, request.title().strip(), request.body(),
                user.getSubject(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        try {
            notes.insert(companyId, note);
        } catch (RuntimeException e) {
            core.release(companyId, FEATURE, 1, "note-undo-" + id); // give the unit back
            throw e;
        }
        return ResponseEntity.created(URI.create("/api/companies/" + companyId + "/notes/" + id)).body(note);
    }

    @DeleteMapping("/{noteId}")
    ResponseEntity<Void> delete(@PathVariable UUID companyId, @PathVariable UUID noteId,
            @AuthenticationPrincipal Jwt user) {
        authorize(companyId, user, null);
        if (!notes.delete(companyId, noteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            core.release(companyId, FEATURE, 1, "note-delete-" + noteId);
        } catch (RuntimeException e) {
            // The note is gone; never fail the delete. The usage count stays one too high and is logged for follow-up.
            log.warn("Could not release usage for deleted note {}: {}", noteId, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private void authorize(UUID companyId, Jwt user, String feature) {
        if (tenants.find(companyId).filter(TenantRepository.Tenant::active).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // not provisioned here
        }
        CoreClient.Access access = core.access(companyId, user.getSubject(), feature);
        if (access.allowed()) {
            return;
        }
        if ("NOT_A_MEMBER".equals(access.reason()) || "UNKNOWN_USER".equals(access.reason())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // do not reveal other companies
        }
        HttpStatus status = "LIMIT_REACHED".equals(access.reason()) ? HttpStatus.CONFLICT : HttpStatus.FORBIDDEN;
        throw new ResponseStatusException(status, "Access denied: " + access.reason());
    }
}
