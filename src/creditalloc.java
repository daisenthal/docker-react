package com.splwg.cm.domain.admin.serviceAgreementType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.dom4j.io.OutputFormat;

import com.ibm.icu.math.BigDecimal;
import com.splwg.base.api.businessObject.BusinessObjectDispatcher;
import com.splwg.base.api.businessObject.BusinessObjectInstance;
import com.splwg.base.api.businessObject.COTSInstanceList;
import com.splwg.base.api.businessObject.COTSInstanceListNode;
import com.splwg.base.api.businessObject.COTSInstanceNode;
import com.splwg.base.api.businessObject.DataAreaInstance;
import com.splwg.base.api.datatypes.Date;
import com.splwg.cm.domain.interests.WorkingFtsDataArea;
import com.splwg.cm.domain.interests.service.FinancialTransactionListRetriever;
import com.splwg.shared.common.Dom4JHelper;
import com.splwg.shared.logging.Logger;
import com.splwg.shared.logging.LoggerFactory;
import com.splwg.tax.domain.admin.debtCategory.DebtCategory;
import com.splwg.tax.domain.admin.debtCategory.DebtCategory_Id;
import com.splwg.tax.domain.admin.debtCategoryPriority.DebtCategoryPriority;
import com.splwg.tax.domain.admin.serviceAgreementType.MessageRepository;
import com.splwg.tax.domain.admin.serviceAgreementType.SaTypeDetermineDetailedBalanceAlgorithmSpot;
import com.splwg.tax.domain.customerinfo.serviceAgreement.ServiceAgreement;
import com.splwg.tax.domain.financial.financialTransaction.FinancialTransaction;
import com.splwg.tax.domain.financial.financialTransaction.FinancialTransaction_Id;

/**
 * This algorithm will determine the detailed balance of an obligation.
 *
 * @AlgorithmComponent ()
 */
public class CmDetermineDetailedBalanceAlgComp_Impl
        extends CmDetermineDetailedBalanceAlgComp_Gen
        implements SaTypeDetermineDetailedBalanceAlgorithmSpot {

    private static final Logger logger = LoggerFactory.getLogger(CmDetermineDetailedBalanceAlgComp_Impl.class);

    private static final String DEBT_CAT_PRIO_ELEM_DEBT_CAT_PRIO_CD = "debtCategoryPriority";
    private static final String DEBT_CAT_PRIO_ELEM_DEBT_CAT_GRP = "debtCategorySequenceInfo";
    private static final String DEBT_CAT_PRIO_ELEM_DEBT_CAT_LIST = "debtCategorySequenceList";
    private static final String DEBT_CAT_PRIO_ELEM_DEBT_CAT_SEQ = "sequenceNumber";
    private static final String DEBT_CAT_PRIO_ELEM_DEBT_CAT_CD = "debtCategory";

    private final FinancialTransactionListRetriever financialTransactionListRetriever = new FinancialTransactionListRetriever();

    private ServiceAgreement sa;
    private Date refDate;
    private boolean useInputFtList;
    private DataAreaInstance ftListDataArea;
    private Date obligationStartDate;
    private boolean updateDataArea = false;

    @Override
    public void setObligation(ServiceAgreement aSa) {
        this.sa = aSa;
    }

    @Override
    public void setReferenceDate(Date referenceDate) {
        this.refDate = referenceDate;
    }

    @Override
    public void setUseInputFtList(boolean willUseInputFtList) {
        this.useInputFtList = willUseInputFtList;
    }

    @Override
    public void setFtListDataArea(DataAreaInstance ftList) {
        this.ftListDataArea = ftList;
    }

    @Override
    public DataAreaInstance getFtListDataArea() {
        return this.ftListDataArea;
    }

    @Override
    public void invoke() {
        logger.debug("Starting Obligation's Detailed  Balance Retriever Algorithm");
        // set here the obligation start date as instance variable
        this.obligationStartDate = this.sa.getStartDate();

        if (isNull(this.refDate)) {
            this.refDate = Date.MAX;
        }
        if (!this.useInputFtList) {
            this.ftListDataArea = this.financialTransactionListRetriever.getFtList(this.sa, this.refDate);
            this.updateDataArea = true;
        }

        WorkingFtsDataArea workingFtsDataArea;
        if (this.ftListDataArea instanceof WorkingFtsDataArea) {
            workingFtsDataArea = ((WorkingFtsDataArea) this.ftListDataArea);
        } else {
            workingFtsDataArea = new WorkingFtsDataArea(this.ftListDataArea);
            this.updateDataArea = true;
        }
        CmPenaltyAndInterestFtListDataHandler ftListData = new CmPenaltyAndInterestFtListDataHandler();
        ftListData.setDataArea(workingFtsDataArea.getData());
        List<CmPenaltyAndInterestFinancialData> ftList = ftListData.getDetailedFtList(this.refDate, this.refDate);
        relieveDebitsUsingCredits(ftListData, ftList);
        if (this.updateDataArea) {
            this.ftListDataArea = workingFtsDataArea.updateDataArea();
        }
        logger.debug("Ending Obligation's Detailed  Balance Retriever Algorithm");
    }

    private void checkFtList(List<CmPenaltyAndInterestFinancialData> ftList) {
        for (CmPenaltyAndInterestFinancialData ftData : ftList) {
            if (ftData.isDebit() && isNull(ftData.getDebtCategory())) {
                FinancialTransaction ft = new FinancialTransaction_Id(ftData.getFtId()).getEntity();
                addError(MessageRepository.debitWithoutDebtCategory(this.sa, ft));
            }
        }
    }

    private void relieveDebitsUsingCredits(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList) {

        List<Date> creditDates = getCreditDates(ftList);
        for (Date creditDate : creditDates) {
            for () {
            }
         
        
            relieveByDebtCategory(ftData, ftList, creditDate);
            if (checkIfDone(ftList, creditDate)) {
                continue;
            }
            relieveByCreditAllocation(ftData, ftList, creditDate);
            if (checkIfDone(ftList, creditDate)) {
                continue;
            }
            relieveRemaining(ftData, ftList, creditDate);
        }

          
    }

    private List<Date> getCreditDates(List<CmPenaltyAndInterestFinancialData> ftList) {
        List<Date> aList = new ArrayList<>();
        for (CmPenaltyAndInterestFinancialData ftData : ftList) {
            if (ftData.isCredit() && isNull(ftData.getCancelReason()) && !ftData.getFtBalance().isZero() && ftData.getEffectiveDate().isSameOrBefore(this.refDate)) {
                // handle the case here where a payment is before obligation start date
                if (this.obligationStartDate != null && ftData.getEffectiveDate().isBefore(this.obligationStartDate)) {
                    // we add as date the obligation start date in order payments before this date
                    // are allocated only to the main claim and not to the interest
                    aList.add(this.obligationStartDate);
                } else {
                    aList.add(ftData.getEffectiveDate());
                }
            }
        }
        if (!aList.contains(this.refDate)) {
            aList.add(this.refDate);
        }
        Collections.sort(aList);
        return aList;
    }

    private void relieveByDebtCategoryAndAssessment(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (!isCreditWithILD(creditData) && creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate) && !isNull(creditData.getAssessmentFtId()) && !isNull(creditData.getDebtCategory())) {
                for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                    if (isNull(debitData.getAssessmentFtId())) {
                        continue;
                    }
                    if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate) && debitData.getDebtCategory().equals(creditData.getDebtCategory()) && debitData.getAssessmentFtId().equals(creditData.getAssessmentFtId())) {
                        ftData.allocateCredit(debitData, creditData);
                        if (creditData.getFtBalance().isZero()) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private void relieveCreditsWithILDByDebtCategoryAndAssessment(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (isCreditWithILD(creditData) && creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate) && !isNull(creditData.getAssessmentFtId()) && !isNull(creditData.getDebtCategory())) {
                for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                    if (isNull(debitData.getAssessmentFtId())) {
                        continue;
                    }
                    if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate) && debitData.getDebtCategory().equals(creditData.getDebtCategory()) && debitData.getAssessmentFtId().equals(creditData.getAssessmentFtId())) {
                        if (!isAfterInterestLimitationDate(debitData, creditData)) {
                            ftData.allocateCredit(debitData, creditData);
                            if (creditData.getFtBalance().isZero()) {
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void relieveByAssessment(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (!isCreditWithILD(creditData) && creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate) && !isNull(creditData.getAssessmentFtId())) {
                List<DebtCategory> debtCatList = getDebtCategoryList(getDebtCategoryPriority(creditData));
                findDebits: for (DebtCategory debtCategory : debtCatList) {
                    for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                        if (isNull(debitData.getAssessmentFtId())) {
                            continue;
                        }
                        if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate) && debitData.getDebtCategory().equals(debtCategory) && debitData.getAssessmentFtId().equals(creditData.getAssessmentFtId())) {
                            ftData.allocateCredit(debitData, creditData);
                            if (creditData.getFtBalance().isZero()) {
                                break findDebits;
                            }
                        }
                    }
                }
            }
        }
    }

    private void relieveCreditsWithILDByAssessment(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (isCreditWithILD(creditData) && creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate) && !isNull(creditData.getAssessmentFtId())) {
                List<DebtCategory> debtCatList = getDebtCategoryList(getDebtCategoryPriority(creditData));
                findDebits: for (DebtCategory debtCategory : debtCatList) {
                    for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                        if (isNull(debitData.getAssessmentFtId())) {
                            continue;
                        }
                        if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate) && debitData.getDebtCategory().equals(debtCategory) && debitData.getAssessmentFtId().equals(creditData.getAssessmentFtId())) {
                            if (!isAfterInterestLimitationDate(debitData, creditData)) {
                                ftData.allocateCredit(debitData, creditData);
                                if (creditData.getFtBalance().isZero()) {
                                    break findDebits;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void relieveByDebtCategory(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (!isCreditWithILD(creditData) && creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate) && !isNull(creditData.getDebtCategory())) {
                for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                    if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate) && debitData.getDebtCategory().equals(creditData.getDebtCategory())) {
                        ftData.allocateCredit(debitData, creditData);
                        if (creditData.getFtBalance().isZero()) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private void relieveCreditsWithILDByDebtCategory(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (isCreditWithILD(creditData) && creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate) && !isNull(creditData.getDebtCategory())) {
                for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                    if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate) && debitData.getDebtCategory().equals(creditData.getDebtCategory())) {
                        if (!isAfterInterestLimitationDate(debitData, creditData)) {
                            ftData.allocateCredit(debitData, creditData);
                            if (creditData.getFtBalance().isZero()) {
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void relieveByCreditAllocation(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (!isCreditWithILD(creditData) && creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate)) {
                List<DebtCategory> debtCatList = getDebtCategoryList(getDebtCategoryPriority(creditData));
                findDebits: for (DebtCategory debtCategory : debtCatList) {
                    for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                        if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate) && debitData.getDebtCategory().equals(debtCategory)) {
                            ftData.allocateCredit(debitData, creditData);
                            if (creditData.getFtBalance().isZero()) {
                                break findDebits;
                            }
                        }
                    }
                }
            }
        }
    }

    private void relieveCreditsWithILDByCreditAllocation(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (isCreditWithILD(creditData) && creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate)) {
                List<DebtCategory> debtCatList = getDebtCategoryList(getDebtCategoryPriority(creditData));
                findDebits: for (DebtCategory debtCategory : debtCatList) {
                    for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                        if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate) && debitData.getDebtCategory().equals(debtCategory)) {
                            if (!isAfterInterestLimitationDate(debitData, creditData)) {
                                ftData.allocateCredit(debitData, creditData);
                                if (creditData.getFtBalance().isZero()) {
                                    break findDebits;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void relieveRemaining(CmPenaltyAndInterestFtListDataHandler ftData,
            List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        for (CmPenaltyAndInterestFinancialData creditData : ftList) {
            if (creditData.isCredit() && !creditData.getFtBalance().isZero() && creditData.getEffectiveDate().isSameOrBefore(creditDate)) {
                for (CmPenaltyAndInterestFinancialData debitData : ftList) {
                    if (debitData.isDebit() && !debitData.getFtBalance().isZero() && debitData.getEffectiveDate().isSameOrBefore(creditDate)) {
                        if (!isAfterInterestLimitationDate(debitData, creditData)) {
                            ftData.allocateCredit(debitData, creditData);
                            if (creditData.getFtBalance().isZero()) {
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isAfterInterestLimitationDate(CmPenaltyAndInterestFinancialData debitData, CmPenaltyAndInterestFinancialData creditData) {
        if (creditData.getInterestLimitationDate() != null) {
            if (debitData.getEffectiveDate().isAfter(creditData.getInterestLimitationDate())) {
                return true;
            }
        }
        return false;
    }

    private boolean isCreditWithILD(CmPenaltyAndInterestFinancialData creditData) {
        return !isNull(creditData.getInterestLimitationDate());
    }

    private DebtCategoryPriority getDebtCategoryPriority(CmPenaltyAndInterestFinancialData creditData) {
        DebtCategoryPriority dcp = this.sa.getServiceAgreementType().fetchDebtCategoryPriority();
        if (!isNull(creditData.getDebtCategoryPriority())) {
            dcp = creditData.getDebtCategoryPriority();
        } else {
            if (creditData.getFtType().isAdjustment()) {
                if (!isNull(creditData.getAdjType().fetchDebtCategoryPriority())) {
                    dcp = creditData.getAdjType().fetchDebtCategoryPriority();
                }
            }
        }
        if (isNull(dcp)) {
            addError(MessageRepository.creditWithoutDebtCategoryPriority(this.sa));
        }
        return dcp;
    }

    private List<DebtCategory> getDebtCategoryList(DebtCategoryPriority dcp) {
        // read the BO
        String dcpBoName = dcp.getBusinessObject().getId().getIdValue();
        BusinessObjectInstance dcpBo = BusinessObjectInstance.create(dcpBoName);
        dcpBo.set(DEBT_CAT_PRIO_ELEM_DEBT_CAT_PRIO_CD, dcp.getId().getTrimmedValue());
        dcpBo = BusinessObjectDispatcher.read(dcpBo);
        // build the list of debt categories
        List<DebtCategory> aList = new ArrayList<>();
        COTSInstanceNode grp = dcpBo.getGroup(DEBT_CAT_PRIO_ELEM_DEBT_CAT_GRP);
        COTSInstanceList dcList = grp.getList(DEBT_CAT_PRIO_ELEM_DEBT_CAT_LIST);
        for (Iterator<COTSInstanceListNode> iter = dcList.iterator(); iter.hasNext();) {
            COTSInstanceListNode dcRow = iter.next();
            Number seq = dcRow.getNumber(DEBT_CAT_PRIO_ELEM_DEBT_CAT_SEQ);
            String debtCatId = dcRow.getString(DEBT_CAT_PRIO_ELEM_DEBT_CAT_CD);
            DebtCategory dc = new DebtCategory_Id(debtCatId).getEntity();
            if (!isNull(dc)) {
                aList.add(dc);
            }
        }
        if (aList.size() < 1) {
            addError(MessageRepository.creditWithoutDebtCategoryList(dcp));
        }
        return aList;
    }

    private boolean checkIfDone(List<CmPenaltyAndInterestFinancialData> ftList, Date creditDate) {
        BigDecimal creditTotal = BigDecimal.ZERO;
        BigDecimal debitTotal = BigDecimal.ZERO;
        for (CmPenaltyAndInterestFinancialData ftData : ftList) {
            if (ftData.getEffectiveDate().isAfter(creditDate)) {
                continue;
            }
            if (ftData.isCredit()) {
                creditTotal = creditTotal.add(ftData.getFtBalance().getAmount());
            } else {
                debitTotal = debitTotal.add(ftData.getFtBalance().getAmount());
            }
        }
        if (creditTotal.compareTo(BigDecimal.ZERO) == 0) {
            return true;
        }
        if (debitTotal.compareTo(BigDecimal.ZERO) == 0) {
            return true;
        }
        return false;
    }

    private String reformat(DataAreaInstance da) {
        OutputFormat format = OutputFormat.createPrettyPrint();
        format.setSuppressDeclaration(true);
        format.setNewLineAfterDeclaration(false);
        format.setIndentSize(3);
        String str = null;
        try {
            str = Dom4JHelper.print(da.getDocument(), format);
        } catch (Exception e) {
            // skip reformatting...
            // errors and warnings are to be generated by normal checks.
        }
        return str;
    }
}