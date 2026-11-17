package com.stackandspine;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "stackandspine.storage=memory")
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

    @Test
    void indexRendersFullPageAndHtmxFragment() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"main-layout\"")))
                .andExpect(content().string(containsString("id=\"add-item-form\"")))
                .andExpect(content().string(containsString("id=\"catalog-grid\"")));

        mockMvc.perform(get("/").header("HX-Request", "true").param("q", "Neal Stephenson"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"catalog-grid\"")))
                .andExpect(content().string(not(containsString("id=\"main-layout\""))));
    }
}
