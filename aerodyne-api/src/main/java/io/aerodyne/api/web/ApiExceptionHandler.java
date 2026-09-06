package io.aerodyne.api.web;

import io.aerodyne.dispatch.cpdlc.CpdlcCodec;
import io.aerodyne.dispatch.notam.NotamParser;
import io.aerodyne.navdata.arinc.Arinc424Parser;
import io.aerodyne.weather.grib.Grib2Exception;
import io.aerodyne.weather.metar.MetarParser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Turns the exceptions the engine raises into HTTP responses.
 *
 * <p>The mapping is by <em>what went wrong</em>, not by exception type for its own sake. A
 * malformed METAR and a route naming a fix that does not exist are both the caller's problem and
 * both {@code 400}. A cycle that is loaded in memory but missing from the tables is nobody's
 * request's fault and is {@code 500}. No navigation data at all is {@code 503}, because the
 * request would succeed once the deployment is finished.
 *
 * <p>Messages are passed through rather than replaced with something generic. The engine's
 * messages say what was wrong and which token caused it, and a caller integrating against this
 * needs that far more than it needs a tidy error code.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final URI PROBLEM_BASE = URI.create("https://aerodyne.io/problems/");

    /** A request the engine cannot plan: an unknown aerodrome, an aircraft type it has no tables for. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "unplannable-request",
                "The request cannot be planned", exception.getMessage(), request);
    }

    /** Input the parsers could not read. */
    @ExceptionHandler({
            MetarParser.MetarParseException.class,
            NotamParser.NotamParseException.class,
            CpdlcCodec.CpdlcFormatException.class,
            Grib2Exception.class
    })
    public ProblemDetail unreadable(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "unreadable-input",
                "The submitted data could not be read", exception.getMessage(), request);
    }

    /** Something that does not exist. */
    @ExceptionHandler({PlanningController.PlanNotFoundException.class, NotFoundException.class})
    public ProblemDetail notFound(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found",
                exception.getMessage(), request);
    }

    /** A request whose body failed validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException exception,
                                 HttpServletRequest request) {
        List<String> failures = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "invalid-request",
                "The request is not valid", String.join("; ", failures), request);
        detail.setProperty("failures", failures);
        return detail;
    }

    /**
     * The engine has nothing to work with yet. Distinguished from a bad request because the
     * caller should retry rather than change anything.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail notReady(IllegalStateException exception, HttpServletRequest request) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("no navigation data")) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "no-navigation-data",
                    "No navigation data is loaded", message, request);
        }
        log.error("unexpected state handling {}", request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "The request could not be completed", message, request);
    }

    /** Anything the engine did not anticipate is logged in full and reported without detail. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        log.error("unhandled exception for {}", request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "The request could not be completed",
                "An unexpected error occurred; the details are in the server log", request);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title,
                                         String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(PROBLEM_BASE.resolve(type));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /** Raised when a resource addressed by the request does not exist. */
    public static class NotFoundException extends RuntimeException {

        public NotFoundException(String what, String identifier) {
            super("no " + what + " with identifier '" + identifier + "'");
        }
    }
}
