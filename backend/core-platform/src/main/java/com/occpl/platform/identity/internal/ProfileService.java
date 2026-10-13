package com.occpl.platform.identity.internal;

import com.occpl.platform.identity.api.UserDirectory;
import com.occpl.platform.identity.api.UserSummary;
import com.occpl.platform.identity.api.UserStatus;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService implements UserDirectory {

    private final PlatformUserRepository users;
    private final UserProfileRepository profiles;
    private final UserIdentityRepository identities;
    private final IdentityProperties properties;
    private final Clock clock;

    ProfileService(PlatformUserRepository users, UserProfileRepository profiles, UserIdentityRepository identities,
            IdentityProperties properties, Clock clock) {
        this.users = users;
        this.profiles = profiles;
        this.identities = identities;
        this.properties = properties;
        this.clock = clock;
    }

    public record ProfileView(UUID userId, String email, String displayName, String phone, String locale, String timeZone) {
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> find(UUID userId) {
        return users.findById(userId).map(u -> new UserSummary(u.id(), u.email(),
                profiles.findById(u.id()).map(UserProfile::displayName).orElse(null), u.status()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findAll(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = profiles.findAllById(userIds).stream()
                .filter(p -> p.displayName() != null)
                .collect(Collectors.toMap(UserProfile::userId, UserProfile::displayName));
        return users.findAllById(userIds).stream()
                .map(u -> new UserSummary(u.id(), u.email(), names.get(u.id()), u.status()))
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findBySubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return identities.findByProviderAndSubject(properties.providerId(), subject)
                .flatMap(identity -> find(identity.userId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UserSummary findOrCreateInvited(String email) {
        String normalized = PlatformUser.normalizeEmail(email);
        PlatformUser user = users.findByEmail(normalized).orElseGet(() ->
                users.save(new PlatformUser(UuidV7.generate(clock), normalized, UserStatus.INVITED, clock.instant())));
        if (user.status() == UserStatus.DISABLED) {
            throw new ApiException(ErrorCode.CONFLICT, "This user's account is disabled and cannot be invited.");
        }
        return new UserSummary(user.id(), user.email(),
                profiles.findById(user.id()).map(UserProfile::displayName).orElse(null), user.status());
    }

    @Transactional(readOnly = true)
    public ProfileView get(UUID userId) {
        PlatformUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        return profiles.findById(userId)
                .map(p -> new ProfileView(userId, user.email(), p.displayName(), p.phone(), p.locale(), p.timeZone()))
                .orElse(new ProfileView(userId, user.email(), null, null, null, null));
    }

    @Transactional
    public ProfileView update(UUID userId, String displayName, String phone, String locale, String timeZone) {
        PlatformUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        UserProfile profile = profiles.findById(userId)
                .orElseGet(() -> profiles.save(new UserProfile(userId, null, clock.instant())));
        profile.update(displayName, phone, locale, timeZone, clock.instant());
        return new ProfileView(userId, user.email(), profile.displayName(), profile.phone(), profile.locale(),
                profile.timeZone());
    }
}
