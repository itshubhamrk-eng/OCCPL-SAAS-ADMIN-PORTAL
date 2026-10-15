package com.occpl.sample;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every query is scoped by company id (the tenant). */
@Repository
class NoteRepository {

    private final JdbcClient jdbc;

    NoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Note(UUID id, String title, String body, String createdBy, Instant createdAt) {
    }

    void insert(UUID companyId, Note note) {
        jdbc.sql("""
                insert into note (id, company_id, title, body, created_by, created_at)
                values (:id, :company, :title, :body, :by, :at)""")
                .param("id", note.id().toString()).param("company", companyId.toString())
                .param("title", note.title()).param("body", note.body()).param("by", note.createdBy())
                .param("at", Timestamp.from(note.createdAt())).update();
    }

    List<Note> list(UUID companyId) {
        return jdbc.sql("""
                select id, title, body, created_by, created_at from note
                where company_id = :company order by created_at desc, id desc limit 200""")
                .param("company", companyId.toString())
                .query((rs, i) -> new Note(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant())).list();
    }

    boolean delete(UUID companyId, UUID noteId) {
        return jdbc.sql("delete from note where company_id = :company and id = :id")
                .param("company", companyId.toString()).param("id", noteId.toString()).update() == 1;
    }

    void deleteAllOfCompany(UUID companyId) {
        jdbc.sql("delete from note where company_id = :company").param("company", companyId.toString()).update();
    }
}
