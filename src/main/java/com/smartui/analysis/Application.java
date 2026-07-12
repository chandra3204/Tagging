package com.smartui.analysis;

import com.smartui.analysis.model.User;
import com.smartui.analysis.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public CommandLineRunner initDatabase(UserRepository userRepository) {
        return args -> {
            // Create uploads directory if it doesn't exist
            File uploadsDir = new File("uploads");
            if (!uploadsDir.exists()) {
                uploadsDir.mkdirs();
                System.out.println("Created uploads directory at: " + uploadsDir.getAbsolutePath());
            }

            // Seed default manager user
            String defaultEmail = "manager@app.com";
            if (!userRepository.existsByEmail(defaultEmail)) {
                User manager = new User();
                manager.setEmail(defaultEmail);
                // In a production app, we would hash the password.
                // For this beginner-friendly implementation, we use direct string/simple comparison.
                manager.setPassword("manager123");
                userRepository.save(manager);
                System.out.println("Default manager account seeded successfully.");
            } else {
                System.out.println("Manager account already exists.");
            }
        };
    }
}
