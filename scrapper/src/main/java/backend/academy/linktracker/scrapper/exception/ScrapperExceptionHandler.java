package backend.academy.linktracker.scrapper.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ScrapperExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ScrapperExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail applicationError(ApiException exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Эта ссылка уже отслеживается.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class})
    ProblemDetail invalidRequest() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Проверьте поля: название до 120 символов, URL до 2048, до 10 тегов по 32 символа.");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail databaseError(DataAccessException exception) {
        log.error("Database operation failed", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Не удалось обратиться к базе данных. Попробуйте позже.");
    }
}
