package com.ontoquery.ontology;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OntologyRepositoryReloadTest {

    @Test
    void reload重建模型并换引用() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Map.of 禁止 null 值，含 null 的行用 HashMap 手工填充
        Map<String, Object> classRow = new HashMap<>(16);
        classRow.put("id", 1L);
        classRow.put("code", "CLS_DRUG");
        classRow.put("name_cn", "药品");
        classRow.put("parent_id", null);
        classRow.put("color", "#34d399");
        classRow.put("sort_no", 1);
        classRow.put("remark", null);
        // 所有 queryForList 默认空表，ont_class 查询返回一行
        when(jdbc.queryForList(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("FROM ont_class")) {
                return new ArrayList<>(List.of(classRow));
            }
            return new ArrayList<Map<String, Object>>();
        });
        OntologyRepository repository = new OntologyRepository(jdbc);
        OntologyModel before = repository.getModel();
        assertNotNull(before.classByCode("CLS_DRUG"), "初始加载应含药品类");

        OntologyModel after = repository.reload();

        assertNotSame(before, after, "reload 应换新模型引用");
        assertNotNull(after.classByCode("CLS_DRUG"), "reload 后数据仍在");
        assertSame(after, repository.getModel(), "getModel 返回最新模型");
        verify(jdbc, atLeast(2)).queryForList(anyString());
    }
}
