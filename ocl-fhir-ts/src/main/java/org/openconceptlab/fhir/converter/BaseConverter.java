package org.openconceptlab.fhir.converter;

import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeSystem;
import org.openconceptlab.fhir.model.*;
import org.openconceptlab.fhir.repository.*;
import org.openconceptlab.fhir.util.OclFhirUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static org.openconceptlab.fhir.util.OclFhirConstants.*;
import static org.openconceptlab.fhir.util.OclFhirUtil.*;
import static org.openconceptlab.fhir.util.OclFhirUtil.gson;

@Component
public class BaseConverter {

    protected SourceRepository sourceRepository;
    protected ConceptRepository conceptRepository;
    protected OclFhirUtil oclFhirUtil;
    protected UserProfile oclUser;
    protected ConceptsSourceRepository conceptsSourceRepository;
    protected AuthtokenRepository authtokenRepository;
    protected UserProfilesOrganizationRepository userProfilesOrganizationRepository;
    protected OrganizationRepository organizationRepository;
    protected UserRepository userRepository;
    protected SimpleJdbcInsert insertLocalizedText;
    protected SimpleJdbcInsert insertConcept;
    protected DataSource dataSource;
    protected JdbcTemplate jdbcTemplate;

    protected static final String insertConceptNamesSql = "insert into concepts_names (localizedtext_id,concept_id) values (?,?)";
    protected static final String insertConceptDescSql = "insert into concepts_descriptions (localizedtext_id,concept_id) values (?,?)";
    protected static final String updateConceptVersionSql = "update concepts set version = ? where id = ?";
    protected static final String insertConceptsSources = "insert into concepts_sources (concept_id,source_id) values (?,?)";

    @Autowired
    public BaseConverter(SourceRepository sourceRepository, ConceptRepository conceptRepository, OclFhirUtil oclFhirUtil,
                         UserProfile oclUser, ConceptsSourceRepository conceptsSourceRepository, DataSource dataSource,
                         AuthtokenRepository authtokenRepository, UserProfilesOrganizationRepository userProfilesOrganizationRepository,
                         OrganizationRepository organizationRepository, UserRepository userRepository) {
        this.sourceRepository = sourceRepository;
        this.conceptRepository = conceptRepository;
        this.oclFhirUtil = oclFhirUtil;
        this.oclUser = oclUser;
        this.conceptsSourceRepository = conceptsSourceRepository;
        this.dataSource = dataSource;
        this.authtokenRepository = authtokenRepository;
        this.userProfilesOrganizationRepository = userProfilesOrganizationRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void init() {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        insertLocalizedText = new SimpleJdbcInsert(jdbcTemplate).withTableName("localized_texts");
        insertConcept = new SimpleJdbcInsert(jdbcTemplate).withTableName("concepts");
    }

    protected BaseOclEntity validateOwner(String org, String username) {
        if (isValid(org)) {
            Organization organization = organizationRepository.findByMnemonic(org);
            if (organization == null) {
                throw new InvalidRequestException("The organization of id = " + org + " does not exist.");
            } else {
                return organization;
            }
        } else {
            UserProfile userProfile = userRepository.findByUsername(username);
            if (userProfile == null) {
                throw new InvalidRequestException("The user of username = " + username + " does not exist.");
            } else {
                return userProfile;
            }
        }
    }

    protected AuthtokenToken validateToken(String authToken) {
        if (isValid(authToken)) {
            String tokenStr = authToken.replaceAll("Token\\s+", EMPTY);
            return authtokenRepository.findByKey(tokenStr.trim());
        } else {
            throw new AuthenticationException("The authentication token is not provided.");
        }
    }

    protected void authenticate(AuthtokenToken token, String username, String org) {
        if (token == null) {
            throw new AuthenticationException("Invalid authentication token.");
        }
        if (isValid(username)) {
            if (!username.equals(token.getUserProfile().getUsername())) {
                throw new AuthenticationException("The " + username + " is not authorized to use the token provided.");
            }
        } else if (isValid(org)) {
            boolean isMember = userProfilesOrganizationRepository.findByOrganizationMnemonic(org)
                    .stream()
                    .map(UserProfilesOrganization::getUserProfile)
                    .anyMatch(f -> f.getUsername().equals(token.getUserProfile().getUsername())
                            && f.getAuthtokenTokens().stream().anyMatch(t -> t.getKey().equals(token.getKey())));
            if (!isMember) {
                throw new AuthenticationException("The user " + token.getUserProfile().getUsername() + " is not authorized to access " +
                        org + " organization.");
            }
        } else {
            throw new InvalidRequestException("Owner can not be empty.");
        }
    }

    protected void validateId(String username, String org, String id, String version, String resourceType) {
        if (CODESYSTEM.equals(resourceType)) {
            Source userSource = sourceRepository.findFirstByMnemonicAndVersionAndUserIdUsername(id, version, username);
            Source orgSource = sourceRepository.findFirstByMnemonicAndVersionAndOrganizationMnemonic(id, version, org);
            if (userSource != null || orgSource != null) {
                throw new ResourceVersionConflictException(String.format("The %s %s of version %s already exists.", resourceType, id, version));
            }
        } else {
            throw new InternalErrorException("Invalid resource type.");
        }
    }

    protected void validateCanonicalUrl(String username, String org, String url, String version, String resourceType) {
        if (CODESYSTEM.equals(resourceType)) {
            Source userSource = sourceRepository.findFirstByCanonicalUrlAndVersionAndUserIdUsername(url, version, username);
            Source orgSource = sourceRepository.findFirstByCanonicalUrlAndVersionAndOrganizationMnemonic(url, version, org);
            if (userSource != null || orgSource != null) {
                throw new ResourceVersionConflictException(String.format("The %s of canonical url %s and version %s already exists.", resourceType, url, version));
            }
        } else {
            throw new InternalErrorException("Invalid resource type.");
        }
    }

    protected void addJsonStrings(final CodeSystem codeSystem, final Source source) {
        source.setIdentifier(convertToJsonString(getResIdentifierString(codeSystem), IDENTIFIER));
        if (!codeSystem.getContact().isEmpty())
            source.setContact(convertToJsonString(getResContactString(codeSystem), CONTACT));
        if (!codeSystem.getJurisdiction().isEmpty())
            source.setJurisdiction(convertToJsonString(getResJurisdictionString(codeSystem), JURISDICTION));
    }

    protected void batchUpdateConceptVersion(List<Integer> conceptIds) {
        this.jdbcTemplate.batchUpdate(updateConceptVersionSql, new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement ps, int i)
                    throws SQLException {
                ps.setInt(1, conceptIds.get(i));
                ps.setInt(2, conceptIds.get(i));
            }
            public int getBatchSize() {
                return conceptIds.size();
            }
        });
    }

    protected void batchUpdateConceptSources(List<Integer> conceptIds, Integer sourceId) {
        this.jdbcTemplate.batchUpdate(insertConceptsSources, new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement ps, int i)
                    throws SQLException {
                ps.setInt(1, conceptIds.get(i));
                ps.setInt(2, sourceId);
            }
            public int getBatchSize() {
                return conceptIds.size();
            }
        });
    }

    protected void batchInsertConceptNames(String sql, List<Long> nameIds, Integer conceptId) {
        this.jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement ps, int i)
                    throws SQLException {
                ps.setInt(1, nameIds.get(i).intValue());
                ps.setInt(2, conceptId);
            }
            public int getBatchSize() {
                return nameIds.size();
            }
        });
    }

    protected void batchConcepts(List<Concept> concepts, List<Integer> conceptIds) {
        concepts.forEach(c -> {
            Integer conceptId = insert(insertConcept, toMap(c)).intValue();
            if (!c.getConceptsNames().isEmpty()) {
                List<Long> nameIds = insertRows(
                        c.getConceptsNames().stream().filter(Objects::nonNull).filter(f -> f.getLocalizedText() != null).map(ConceptsName::getLocalizedText).collect(Collectors.toList())
                );
                batchInsertConceptNames(insertConceptNamesSql, nameIds, conceptId);
            }
            if (!c.getConceptsDescriptions().isEmpty()) {
                List<Long> descIds = insertRows(
                        c.getConceptsDescriptions().stream().filter(Objects::nonNull).filter(f -> f.getLocalizedText() != null).map(ConceptsDescription::getLocalizedText).collect(Collectors.toList())
                );
                batchInsertConceptNames(insertConceptDescSql, descIds, conceptId);
            }
            conceptIds.add(conceptId);
        });
    }

    protected List<Long> insertRows(List<LocalizedText> texts) {
        List<Long> keys = new ArrayList<>();
        texts.forEach(t -> {
            keys.add(insert(insertLocalizedText, toMap(t)));
        });
        return keys;
    }

    private Map<String, Object> toMap(LocalizedText text) {
        Map<String, Object> map = new HashMap<>();
        map.put(NAME, text.getName());
        map.put(TYPE, text.getType());
        map.put(LOCALE, text.getLocale());
        map.put(LOCALE_PREFERRED, text.getLocalePreferred());
        map.put(CREATED_AT, text.getCreatedAt());
        return map;
    }

    private Map<String, Object> toMap(Concept obj) {
        Map<String, Object> map = new HashMap<>();
        map.put(PUBLIC_ACCESS, obj.getPublicAccess());
        map.put(IS_ACTIVE, obj.getIsActive());
        map.put(EXTRAS, obj.getExtras());
        map.put(URI, obj.getUri());
        map.put(MNEMONIC, obj.getMnemonic());
        map.put(VERSION, obj.getVersion());
        map.put(RELEASED, obj.getReleased());
        map.put(RETIRED, obj.getRetired());
        map.put(IS_LATEST_VERSION, obj.getIsLatestVersion());
        map.put(NAME, obj.getName());
        map.put(FULL_NAME, obj.getFullName());
        map.put(DEFAULT_LOCALE, obj.getDefaultLocale());
        map.put(CONCEPT_CLASS, obj.getConceptClass());
        map.put(DATATYPE, obj.getDatatype());
        map.put(COMMENT, obj.getComment());
        map.put(CREATED_BY_ID, obj.getCreatedBy().getId());
        map.put(UPDATED_BY_ID, obj.getUpdatedBy().getId());
        map.put(PARENT_ID, obj.getParent().getId());
        map.put(CREATED_AT, obj.getParent().getCreatedAt());
        map.put(UPDATED_AT, obj.getParent().getUpdatedAt());
        return map;
    }

    private Long insert(SimpleJdbcInsert insert, Map<String, Object> parameters) {
        if (!insert.isCompiled())
            insert.usingGeneratedKeyColumns("id");
        Number n = insert.executeAndReturnKeyHolder(parameters).getKey();
        if (n instanceof Long)
            return n.longValue();
        if (n instanceof Integer)
            return Long.valueOf(String.valueOf(n.intValue()));
        return (Long) insert.executeAndReturnKeyHolder(parameters).getKey();
    }

    private String getResContactString(final CodeSystem codeSystem) {
        CodeSystem system = new CodeSystem();
        system.setContact(codeSystem.getContact());
        return getFhirContext().newJsonParser().encodeResourceToString(system);
    }

    private String getResIdentifierString(final CodeSystem codeSystem) {
        CodeSystem system = new CodeSystem();
        system.setIdentifier(codeSystem.getIdentifier());
        return getFhirContext().newJsonParser().encodeResourceToString(system);
    }

    private String getResJurisdictionString(final CodeSystem codeSystem) {
        CodeSystem system = new CodeSystem();
        system.setJurisdiction(codeSystem.getJurisdiction());
        return getFhirContext().newJsonParser().encodeResourceToString(system);
    }

    private String convertToJsonString(String fhirResourceStr, String key) {
        JsonObject object = jsonParser.parse(fhirResourceStr).getAsJsonObject();
        if (object.has(RESOURCE_TYPE))
            object.remove(RESOURCE_TYPE);
        if (object.has(key)) {
            if (object.get(key) instanceof JsonArray) {
                return gson.toJson(object.getAsJsonArray(key));
            } else {
                return gson.toJson(object.getAsJsonObject(key));
            }
        }
        return EMPTY_JSON;
    }

    protected String getStringProperty(List<CodeSystem.ConceptPropertyComponent> properties, String property) {
        Optional<CodeSystem.ConceptPropertyComponent> component = properties.parallelStream().filter(p -> property.equals(p.getCode())).findAny();
        if (component.isPresent() && isValid(component.get().getValueStringType().getValue()))
            return component.get().getValueStringType().getValue();
        return NA;
    }

    protected boolean getBooleanProperty(List<CodeSystem.ConceptPropertyComponent> properties, String property) {
        Optional<CodeSystem.ConceptPropertyComponent> component = properties.parallelStream().filter(p -> property.equals(p.getCode())).findAny();
        if (component.isPresent()) {
            BooleanType value = component.get().getValueBooleanType();
            if(value.getValue() != null) return value.getValue();
        }
        return false;
    }

}
