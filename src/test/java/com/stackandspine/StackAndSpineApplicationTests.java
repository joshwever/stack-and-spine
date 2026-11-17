package com.stackandspine;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StackAndSpineApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoadsAndReturnsSeededLibraryThingCatalog() throws Exception {
        mockMvc.perform(get("/api/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(139));

        mockMvc.perform(get("/api/items").param("q", "Neal Stephenson"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].creator").value("Neal Stephenson"));
    }
}
