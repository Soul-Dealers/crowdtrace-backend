package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;

/** The pilot-sized public search population: 10,000 cases, about 70% approved, spread over names, ages, regions and dates. */
final class PublicCaseSeed {
    private PublicCaseSeed() {
    }

    static void insertBulk(JdbcTemplate jdbc, long reporterId) {
        jdbc.update("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region,
                    last_seen_location, physical_description, clothing, circumstances, public_contact_number,
                    review_status, case_status, submitted_at, approved_at, resolved_at, closed_at)
                SELECT ?,
                    (ARRAY['Ama','Kwame','Abena','Yaw','Akosua','Kojo','Adwoa','Kwaku','Efua','Kwabena',
                           'Esi','Nana','Afia','Kwadwo','Yaa','Osei','Mawuli','Ato','Akua','Kofi',
                           'Nii','Mansa','Kweku','Serwaa','Fiifi','Ebo','Dzifa','Ekua','Nana','Yvonne']::text[])[(g % 30) + 1]
                    || ' ' || (ARRAY['Boateng','Asante','Osei','Addo','Owusu','Antwi','Appiah','Agyeman','Darko','Mensima',
                           'Amankwah','Agyapong','Bediako','Frimpong','Acheampong','Tutu','Dzifa','Quansah','Sarpong','Arthur',
                           'Bonsu','Nyarko','Donkor','Annan','Mensima','Adu','Koomson','Obeng','Sowah','Amoah',
                           'Oppong','Ababio','Baffour','Adjei','Kyeremeh','Poku','Asare','Badu','Tetteh','Appau']::text[])[(g % 40) + 1]
                    || ' ' || g,
                    (g % 91)::smallint,
                    (ARRAY['MALE','FEMALE','UNKNOWN']::text[])[(g % 3) + 1],
                    DATE '2026-10-01' - (g % 365),
                    (ARRAY['AHAFO','ASHANTI','BONO','BONO_EAST','CENTRAL','EASTERN','GREATER_ACCRA','NORTH_EAST',
                           'NORTHERN','OTI','SAVANNAH','UPPER_EAST','UPPER_WEST','VOLTA','WESTERN','WESTERN_NORTH']::text[])[(g % 16) + 1],
                    'Accra', 'Average build', 'Casual clothes', 'Background search fixture', '+233200000000',
                    CASE WHEN g % 10 < 7 THEN 'APPROVED'
                         WHEN g % 10 < 9 THEN 'SUBMITTED' ELSE 'REJECTED' END,
                    CASE WHEN g % 10 < 7 THEN
                        (ARRAY['MISSING','FOUND_SAFE','FOUND_DECEASED','CLOSED']::text[])[(g % 4) + 1]
                        ELSE NULL END,
                    TIMESTAMP '2026-10-01 00:00:00' - g * INTERVAL '1 minute',
                    CASE WHEN g % 10 < 7
                         THEN TIMESTAMP '2026-10-01 00:00:00' - g * INTERVAL '1 minute' ELSE NULL END,
                    CASE WHEN g % 10 < 7 AND g % 4 <> 0
                         THEN TIMESTAMP '2026-10-01 00:00:00' - g * INTERVAL '1 minute' ELSE NULL END,
                    CASE WHEN g % 10 < 7 AND g % 4 = 3
                         THEN TIMESTAMP '2026-10-01 00:00:00' - g * INTERVAL '1 minute' ELSE NULL END
                FROM generate_series(1, 10000) AS generated(g)
                """, reporterId);
    }
}
