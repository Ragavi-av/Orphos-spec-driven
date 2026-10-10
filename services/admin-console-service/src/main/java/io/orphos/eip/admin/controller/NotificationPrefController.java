package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.util.CurrentUser;
import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.service.NotificationPrefService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * C6 - notification preferences. Available to any authenticated user for
 * their own preferences; admins can manage any user's preferences via
 * the same endpoint by setting {@code userKey}.
 */
@RestController
@RequestMapping("/api/notifications/prefs")
public class NotificationPrefController {

    private final NotificationPrefService prefs;

    public NotificationPrefController(NotificationPrefService prefs) {
        this.prefs = prefs;
    }

    @GetMapping
    public EmailDtos.NotificationPrefView mine() {
        return prefs.get(CurrentUser.key());
    }

    @PutMapping
    public EmailDtos.NotificationPrefView update(@Valid @RequestBody EmailDtos.NotificationPrefRequest req) {
        // Non-admins cannot manage someone else's preferences.
        if (req.userKey() != null && !req.userKey().equals(CurrentUser.key())
                && !CurrentUser.isAdmin()) {
            throw new IllegalStateException("only admins can manage other users' preferences");
        }
        return prefs.save(CurrentUser.key(), req);
    }
}
