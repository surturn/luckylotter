package com.lucklotter.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin login (FR-6, §10). */
public record LoginRequest(

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    @Size(max = 255)
    String email,

    @NotBlank(message = "password is required")
    @Size(max = 200)
    String password
) {
}
