package io.aerodyne.routing;

/**
 * Something the route expander could not do cleanly.
 *
 * <p>A filed route comes from a human, an airline system, or a previous cycle's data, and it is
 * routinely almost-right: a fix that no longer exists, an airway that does not connect the two
 * points claimed for it, a level group in a position nothing expects. The expander produces the
 * best route it can and records what it could not honour, rather than either failing outright or
 * silently dropping the token.
 *
 * @param token   the route token the problem is about
 * @param message what went wrong
 * @param dropped true when the token contributed nothing to the expanded route
 */
public record RouteWarning(String token, String message, boolean dropped) {

    public static RouteWarning dropped(String token, String message) {
        return new RouteWarning(token, message, true);
    }

    public static RouteWarning note(String token, String message) {
        return new RouteWarning(token, message, false);
    }

    @Override
    public String toString() {
        return token + ": " + message;
    }
}
