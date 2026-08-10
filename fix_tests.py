import os

f1=r'src\test\java\com\apms\domain\ai\service\AiExtractionReviewTest.java'
f2=r'src\test\java\com\apms\domain\ai\service\ExtractionMergeServiceGuardTest.java'

c1=open(f1, 'r', encoding='utf-8').read()
c1=c1.replace('.reviewStatus(ExtractionReviewStatus.PENDING)', '.managerReviewStatus(ExtractionReviewStatus.PENDING)')
c1=c1.replace('request.setReviewStatus(ExtractionReviewStatus.EDITED)', 'request.setStaffReviewStatus(StaffFieldReviewStatus.EDITED); request.setManager(false)')
c1=c1.replace('request.setReviewStatus(ExtractionReviewStatus.ACCEPTED)', 'request.setManagerReviewStatus(ExtractionReviewStatus.ACCEPTED); request.setManager(true)')
c1=c1.replace('reviewed.getReviewStatus()', 'reviewed.getStaffReviewStatus()')
c1=c1.replace('reviewed.getReviewedValue()', 'reviewed.getStaffReviewedValue()')
c1=c1.replace('reviewed.getReviewComment()', 'reviewed.getManagerComment()')
c1=c1.replace('reviewed.getReviewedByUserId()', 'reviewed.getStaffReviewedByUserId()')
c1=c1.replace('reviewed.getReviewedAt()', 'reviewed.getStaffReviewedAt()')
c1=c1.replace('result.getFieldResults().get("legalName").getReviewStatus()', 'result.getFieldResults().get("legalName").getManagerReviewStatus()')
c1=c1.replace('result.getFieldResults().get("newField").getReviewStatus()', 'result.getFieldResults().get("newField").getManagerReviewStatus()')
c1=c1.replace('setReviewStatus(ExtractionReviewStatus.', 'setManagerReviewStatus(ExtractionReviewStatus.')
c1=c1.replace('assertEquals(ExtractionReviewStatus.EDITED, reviewed.getStaffReviewStatus())', 'assertEquals(StaffFieldReviewStatus.EDITED, reviewed.getStaffReviewStatus())')
open(f1, 'w', encoding='utf-8').write(c1)

c2=open(f2, 'r', encoding='utf-8').read()
c2=c2.replace('.reviewStatus(ExtractionReviewStatus.ACCEPTED)', '.managerReviewStatus(ExtractionReviewStatus.ACCEPTED)')
c2=c2.replace('setReviewStatus(ExtractionReviewStatus.', 'setManagerReviewStatus(ExtractionReviewStatus.')
c2=c2.replace('setReviewedValue(', 'setStaffReviewedValue(')
open(f2, 'w', encoding='utf-8').write(c2)
