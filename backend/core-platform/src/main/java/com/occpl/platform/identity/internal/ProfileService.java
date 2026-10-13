package com.occpl.platform.identity.internal;

import com.occpl.platform.identity.api.UserDirectory;
import com.occpl.platform.identity.api.UserSummary;
import com.occpl.platform.shared.error.ApiException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService implements UserDirectory {

    private final PlatformUserRepository users;
    private final UserProfileRepository profiles;
    private final Clock clock;

    ProfileService(PlatformUserRepository users, UserProfileRepository profiles, Clock clock) {
        this.users = users;
        this.profiles = profiles;
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
