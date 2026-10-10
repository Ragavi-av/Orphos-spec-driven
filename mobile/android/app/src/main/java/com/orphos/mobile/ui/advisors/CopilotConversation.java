package com.orphos.mobile.ui.advisors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.orphos.mobile.data.model.CopilotSession;
import com.orphos.mobile.data.model.NlQuery;
import com.orphos.mobile.data.repository.OrphosRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The live AI conversation on the phone. It is the mobile counterpart of the web's
 * ai-copilot/copilotRuntime.js, and it writes the SAME history records.
 * <ul>
 *   <li>Every question goes to nl-query ({@code POST /api/nl-query/ask}).</li>
 *   <li>Each time an answer lands, the session is upserted to
 *       {@code /api/nl-query/me/copilot/sessions/{id}}. That is the store the web
 *       dashboard's History ▸ AI tab lists, so a phone conversation shows up there.</li>
 *   <li>The id format, the 70-character label and the message shape all match the web,
 *       so a session reads the same on either surface.</li>
 * </ul>
 * Only completed answers are stored. A pending question never is, and neither is a failed
 * one: an error message is no use in the executive's history.
 *
 * <p>Not thread-safe: use it from the main thread only, which is where the repository
 * delivers every callback.</p>
 */
public final class CopilotConversation {

    /** Labels are the first question, cut to this length — same as the web. */
    private static final int LABEL_MAX = 70;
    private static final Random RANDOM = new Random();

    /** A question or answer changed (a question was asked, answered, or failed). */
    public interface Listener {
        void onConversationChanged();
    }

    private final OrphosRepository repo;
    private CopilotSession session = newSession();
    /** Messages whose ask failed. They stay on screen but are left out of the stored history. */
    private final List<CopilotSession.Message> failed = new ArrayList<>();

    @Nullable private Listener listener;
    /** Called after a save reaches the server, so the history list can refresh. */
    @Nullable private Runnable onSaved;

    public CopilotConversation(@NonNull OrphosRepository repo) {
        this.repo = repo;
    }

    public void setListener(@Nullable Listener listener) { this.listener = listener; }

    public void setOnSaved(@Nullable Runnable onSaved) { this.onSaved = onSaved; }

    /** Every message on screen, oldest first, including ones pending and ones that failed. */
    public List<CopilotSession.Message> messages() {
        return session.messages;
    }

    public boolean isFailed(CopilotSession.Message m) {
        return failed.contains(m);
    }

    public boolean isEmpty() {
        return session.messages.isEmpty();
    }

    /**
     * Ask a question. The message shows at once as pending, and {@code onAnswered} gets it
     * once it has resolved. It is not called if the session was replaced in the meantime:
     * the user has moved on, and dropping a stale answer into a fresh thread is worse than
     * losing it (the web applies the same rule).
     */
    public void ask(@NonNull String rawQuestion, @Nullable Consumer<CopilotSession.Message> onAnswered) {
        String question = rawQuestion.trim();
        if (question.isEmpty()) return;

        CopilotSession.Message msg = new CopilotSession.Message();
        msg.id = genId();
        msg.question = question;
        msg.timestamp = Instant.now().toString();
        msg.loading = true;
        session.messages.add(msg);
        changed();

        final CopilotSession asked = session;
        repo.ask(question, (resp, fromMock) -> {
            if (session != asked || !asked.messages.contains(msg)) return;
            msg.loading = false;
            if (resp != null && resp.error == null && resp.answer != null && !resp.answer.isEmpty()) {
                msg.answer = resp.answer;
                msg.followups = resp.followups != null ? resp.followups : new ArrayList<>();
                msg.trust = resp.trust;
                // Same display rule as the web: the family that actually answered beats
                // the routing key, which mis-names the family for historical ids.
                // Never null: the web footer renders it verbatim ("12:04 · undefined").
                msg.provider = resp.modelFamily != null ? resp.modelFamily
                        : (resp.provider != null ? resp.provider : "");
                persist(null);
            } else {
                // The service's own reason (e.g. no provider assigned) where there is one.
                // Null means unreachable, and the screen shows its own copy for that.
                msg.answer = resp != null ? resp.error : null;
                failed.add(msg);
            }
            changed();
            if (onAnswered != null) onAnswered.accept(msg);
        });
    }

    /**
     * Close the current session (it is saved with {@code endedAt}, like the web's
     * "new chat") and start an empty one.
     */
    public void startNewSession() {
        persist(Instant.now().toString());
        session = newSession();
        failed.clear();
        changed();
    }

    /** Best-effort upsert of the completed messages. A failed save never breaks the chat. */
    private void persist(@Nullable String endedAt) {
        List<CopilotSession.Message> completed = new ArrayList<>();
        for (CopilotSession.Message m : session.messages) {
            if (!m.loading && !failed.contains(m)) completed.add(m);
        }
        if (completed.isEmpty()) return;

        CopilotSession snapshot = new CopilotSession();
        snapshot.id = session.id;
        snapshot.startedAt = session.startedAt;
        snapshot.endedAt = endedAt;
        String first = completed.get(0).question;
        snapshot.label = first.length() > LABEL_MAX ? first.substring(0, LABEL_MAX) : first;
        snapshot.messages = completed;

        repo.saveCopilotSession(snapshot, (ok, fromMock) -> {
            if (Boolean.TRUE.equals(ok) && onSaved != null) onSaved.run();
        });
    }

    private void changed() {
        if (listener != null) listener.onConversationChanged();
    }

    private static CopilotSession newSession() {
        CopilotSession s = new CopilotSession();
        s.id = genId();
        s.startedAt = Instant.now().toString();
        return s;
    }

    /** {@code <epochMillis>-<5 base-36 chars>} — the web's genId() format. */
    static String genId() {
        StringBuilder suffix = new StringBuilder(5);
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < 5; i++) suffix.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return System.currentTimeMillis() + "-" + suffix;
    }
}
