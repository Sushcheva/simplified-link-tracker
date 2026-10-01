package backend.academy.linktracker.scrapper.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record RegisterRequest(
        @NotBlank(message = "Укажите email.") @Email(message = "Укажите корректный email.")
        @Size(max = 254, message = "Email должен быть не длиннее 254 символов.") String email,
        @NotBlank(message = "Укажите пароль.")
        @Size(min = 10, max = 64, message = "Пароль должен содержать от 10 до 64 символов.") String password) {
    @AssertTrue(message = "Пароль должен занимать не более 72 байт в UTF-8.")
    public boolean isPasswordLengthSupported() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override public String toString() { return "RegisterRequest[credentials redacted]"; }
}
