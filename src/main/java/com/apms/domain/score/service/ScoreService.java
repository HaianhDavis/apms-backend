package com.apms.domain.score.service;

import com.apms.common.event.CandidateApprovedEvent;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.score.ScoreRule;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.dto.ScoreRuleDto;
import com.apms.domain.score.dto.ScoreSnapshotDto;
import com.apms.domain.score.repository.sql.ScoreRuleRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreService {

    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final ScoreRuleRepository scoreRuleRepository;
    private final CompanyProfileRepository profileRepository;

    // ─────────────────────────────────────────────
    // EVENT LISTENER (Score Generation)
    // ─────────────────────────────────────────────

    @EventListener
    @Order(3)
    @Transactional
    public void handleCandidateApprovedEvent(CandidateApprovedEvent event) {
        log.info("ScoreService received CandidateApprovedEvent for candidateId: {}", event.getCandidateId());

        CompanyProfile profile = profileRepository.findByCandidateId(event.getCandidateId())
                .orElse(null);

        if (profile == null) {
            log.error("CompanyProfile not found for candidateId: {}. Cannot generate ScoreSnapshot.", event.getCandidateId());
            return;
        }

        // Mock Score Engine evaluation
        ScoreSnapshot snapshot = ScoreSnapshot.builder()
                .companyId(profile.getCompanyId())
                .projectId(event.getProjectId())
                .candidateId(event.getCandidateId())
                .partnerFitScore(85)
                .competitionLevel(30)
                .riskLevel(15)
                .relationshipStrength(90)
                .totalScore(80)
                .factorsJson("{\"strengths\": [\"Market leader\", \"Strong tech\"], \"weaknesses\": [\"High debt\"]}")
                .ruleVersion("v1.0")
                .generatedBy("SYSTEM")
                .build();

        snapshot = scoreSnapshotRepository.save(snapshot);
        log.info("Generated ScoreSnapshot ID {} for companyId: {}", snapshot.getScoreSnapshotId(), profile.getCompanyId());
    }

    // ─────────────────────────────────────────────
    // READ SNAPSHOTS
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ScoreSnapshotDto> getCompanyScores(String companyId) {
        return scoreSnapshotRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(this::toDto)
                .toList();
    }

    // ─────────────────────────────────────────────
    // MANAGE RULES
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ScoreRuleDto> getAllRules() {
        return scoreRuleRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public ScoreRuleDto createRule(ScoreRuleDto request) {
        ScoreRule rule = ScoreRule.builder()
                .ruleName(request.getRuleName())
                .ruleCategory(request.getRuleCategory())
                .weight(request.getWeight())
                .ruleConditionJson(request.getRuleConditionJson())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();

        return toDto(scoreRuleRepository.save(rule));
    }

    @Transactional
    public ScoreRuleDto updateRule(Long id, ScoreRuleDto request) {
        ScoreRule rule = scoreRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ScoreRule not found: " + id));

        rule.setRuleName(request.getRuleName());
        rule.setRuleCategory(request.getRuleCategory());
        rule.setWeight(request.getWeight());
        rule.setRuleConditionJson(request.getRuleConditionJson());
        if (request.getIsActive() != null) {
            rule.setIsActive(request.getIsActive());
        }

        return toDto(scoreRuleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id) {
        if (!scoreRuleRepository.existsById(id)) {
            throw new ResourceNotFoundException("ScoreRule not found: " + id);
        }
        scoreRuleRepository.deleteById(id);
    }

    // ─────────────────────────────────────────────
    // MAPPERS
    // ─────────────────────────────────────────────

    private ScoreSnapshotDto toDto(ScoreSnapshot s) {
        return ScoreSnapshotDto.builder()
                .scoreSnapshotId(s.getScoreSnapshotId())
                .companyId(s.getCompanyId())
                .projectId(s.getProjectId())
                .candidateId(s.getCandidateId())
                .partnerFitScore(s.getPartnerFitScore())
                .competitionLevel(s.getCompetitionLevel())
                .riskLevel(s.getRiskLevel())
                .relationshipStrength(s.getRelationshipStrength())
                .totalScore(s.getTotalScore())
                .factorsJson(s.getFactorsJson())
                .ruleVersion(s.getRuleVersion())
                .generatedBy(s.getGeneratedBy())
                .createdAt(s.getCreatedAt())
                .build();
    }

    private ScoreRuleDto toDto(ScoreRule r) {
        return ScoreRuleDto.builder()
                .id(r.getId())
                .ruleName(r.getRuleName())
                .ruleCategory(r.getRuleCategory())
                .weight(r.getWeight())
                .ruleConditionJson(r.getRuleConditionJson())
                .isActive(r.getIsActive())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
