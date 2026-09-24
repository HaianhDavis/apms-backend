//package com.apms;
//
//import com.apms.common.enums.SubmissionStatus;
//import com.apms.common.enums.TaskStatus;
//import com.apms.domain.financial.FinancialReportEntry;
//import com.apms.domain.financial.FinancialReportReviewStatus;
//import com.apms.domain.financial.FinancialResearch;
//import com.apms.domain.financial.FinancialResearchStatus;
//import com.apms.domain.financial.repository.FinancialResearchRepository;
//import com.apms.domain.project.ProjectTask;
//import com.apms.domain.project.ProjectTaskSubmission;
//import com.apms.domain.project.repository.sql.ProjectTaskRepository;
//import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Optional;
//import java.util.stream.Collectors;
//
//@SpringBootTest
//public class DevDataResetTest {
//
//    @Autowired
//    private FinancialResearchRepository researchRepository;
//
//    @Autowired
//    private ProjectTaskRepository taskRepository;
//
//    @Autowired
//    private ProjectTaskSubmissionRepository submissionRepository;
//
//    @Test
//    public void resetTestData() {
//        System.out.println("========== STARTING TEST DATA RESET ==========");
//        // Find a research with at least 2 reports
//        List<FinancialResearch> researches = researchRepository.findAll();
//        FinancialResearch targetResearch = null;
//        for (FinancialResearch r : researches) {
//            if (r.getReports() != null && r.getReports().size() >= 2) {
//                targetResearch = r;
//                break;
//            }
//        }
//
//        if (targetResearch == null) {
//            System.out.println("No research found with at least 2 reports.");
//            return;
//        }
//
//        System.out.println("Target Research ID: " + targetResearch.getId());
//        System.out.println("Target Task ID: " + targetResearch.getTaskId());
//
//        // Update Research status
//        targetResearch.setStatus(FinancialResearchStatus.CHANGES_REQUESTED);
//
//        // Update Reports
//        FinancialReportEntry reportA = targetResearch.getReports().get(0);
//        reportA.setReviewStatus(FinancialReportReviewStatus.APPROVED);
//        reportA.setReviewComment("Looks good");
//
//        FinancialReportEntry reportB = targetResearch.getReports().get(1);
//        reportB.setReviewStatus(FinancialReportReviewStatus.CHANGES_REQUESTED);
//        reportB.setReviewComment("Please fix the metrics on page 3");
//
//        for (int i = 2; i < targetResearch.getReports().size(); i++) {
//             targetResearch.getReports().get(i).setReviewStatus(FinancialReportReviewStatus.PENDING_REVIEW);
//        }
//
//        // Empty submittedReportIds to start fresh
//        targetResearch.setSubmittedReportIds(new ArrayList<>());
//        researchRepository.save(targetResearch);
//
//        // Update Task status
//        Optional<ProjectTask> optTask = taskRepository.findById(targetResearch.getTaskId());
//        if (optTask.isPresent()) {
//            ProjectTask task = optTask.get();
//            task.setStatus(TaskStatus.IN_PROGRESS);
//            task.setCompletedAt(null);
//            taskRepository.save(task);
//            System.out.println("Updated ProjectTask " + task.getId() + " to IN_PROGRESS");
//        } else {
//            System.out.println("ProjectTask not found for id: " + targetResearch.getTaskId());
//        }
//
//        // Clean up submissions
//        List<ProjectTaskSubmission> submissions = submissionRepository.findByProjectTask_Id(targetResearch.getTaskId());
//        System.out.println("Found " + submissions.size() + " submissions for task.");
//        for (ProjectTaskSubmission sub : submissions) {
//            if (sub.getStatus() == SubmissionStatus.IN_REVIEW) {
//                System.out.println("Deleting active wrong submission: " + sub.getId());
//                submissionRepository.delete(sub);
//            } else {
//                 System.out.println("Found historical submission: " + sub.getId() + " - " + sub.getStatus());
//            }
//        }
//
//        System.out.println("========== RESET COMPLETE ==========");
//
//        // Report state
//        System.out.println("FinancialResearch: " + targetResearch.getStatus());
//        System.out.println("Report A: " + reportA.getReviewStatus());
//        System.out.println("Report B: " + reportB.getReviewStatus());
//        System.out.println("SubmittedReportIds: " + targetResearch.getSubmittedReportIds());
//    }
//}
