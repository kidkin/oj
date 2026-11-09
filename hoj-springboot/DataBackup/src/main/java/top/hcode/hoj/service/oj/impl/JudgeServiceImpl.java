package top.hcode.hoj.service.oj.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.stereotype.Service;
import top.hcode.hoj.edu.GrowthAccountService;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.common.result.ResultStatus;
import top.hcode.hoj.exception.AccessException;
import top.hcode.hoj.manager.oj.JudgeManager;
import top.hcode.hoj.pojo.dto.SubmitIdListDTO;
import top.hcode.hoj.pojo.dto.SubmitJudgeDTO;
import top.hcode.hoj.pojo.dto.TestJudgeDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.vo.JudgeCaseVO;
import top.hcode.hoj.pojo.vo.JudgeVO;
import top.hcode.hoj.pojo.vo.SubmissionInfoVO;
import top.hcode.hoj.pojo.vo.TestJudgeVO;
import top.hcode.hoj.service.oj.JudgeService;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;

/**
 * @Author: Himit_ZH
 * @Date: 2022/3/11 15:09
 * @Description:
 */
@Service
public class JudgeServiceImpl implements JudgeService {

    @Resource
    private JudgeManager judgeManager;

    @Resource
    private GrowthAccountService growthAccounts;

    @Override
    public CommonResult<Judge> submitProblemJudge(SubmitJudgeDTO judgeDto) {
        try {
            return CommonResult.successResponse(judgeManager.submitProblemJudge(judgeDto));
        } catch (StatusForbiddenException | AccessException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.FORBIDDEN);
        } catch (StatusNotFoundException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.NOT_FOUND);
        } catch (StatusAccessDeniedException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.ACCESS_DENIED);
        } catch (StatusFailException e) {
            return CommonResult.errorResponse(e.getMessage());
        }
    }

    @Override
    public CommonResult<String> submitProblemTestJudge(TestJudgeDTO testJudgeDto) {
        try {
            return CommonResult.successResponse(judgeManager.submitProblemTestJudge(testJudgeDto), "success");
        } catch (StatusForbiddenException | AccessException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.FORBIDDEN);
        } catch (StatusFailException e) {
            return CommonResult.errorResponse(e.getMessage());
        } catch (StatusSystemErrorException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.SYSTEM_ERROR);
        }
    }

    @Override
    public CommonResult<Judge> resubmit(Long submitId) {
        try {
            return CommonResult.successResponse(judgeManager.resubmit(submitId));
        } catch (StatusNotFoundException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.NOT_FOUND);
        } catch (StatusForbiddenException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.FORBIDDEN);
        }
    }

    @Override
    public CommonResult<SubmissionInfoVO> getSubmission(Long submitId) {
        try {
            SubmissionInfoVO result = judgeManager.getSubmission(submitId);
            if (result.getSubmission() != null && result.getSubmission().getSubmitId() != null) {
                result.setGrowthInvalid(growthAccounts.invalidSubmissions(Collections.singletonList(submitId)).contains(submitId));
            }
            return CommonResult.successResponse(result);
        } catch (StatusNotFoundException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.NOT_FOUND);
        } catch (StatusAccessDeniedException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.ACCESS_DENIED);
        }
    }

    @Override
    public CommonResult<TestJudgeVO> getTestJudgeResult(String testJudgeKey) {
        try {
            return CommonResult.successResponse(judgeManager.getTestJudgeResult(testJudgeKey));
        } catch (StatusFailException e) {
            return CommonResult.errorResponse(e.getMessage());
        }
    }

    @Override
    public CommonResult<IPage<JudgeVO>> getJudgeList(Integer limit,
                                                     Integer currentPage,
                                                     Boolean onlyMine,
                                                     String searchPid,
                                                     Integer searchStatus,
                                                     String searchUsername,
                                                     Boolean completeProblemID,
                                                     Long gid) {
        try {
            IPage<JudgeVO> result = judgeManager.getJudgeList(limit,
                    currentPage,
                    onlyMine,
                    searchPid,
                    searchStatus,
                    searchUsername,
                    completeProblemID,
                    gid);
            growthAccounts.annotateSubmissions(result.getRecords());
            return CommonResult.successResponse(result);
        } catch (StatusAccessDeniedException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.ACCESS_DENIED);
        }
    }

    @Override
    public CommonResult<Void> updateSubmission(Judge judge) {
        try {
            judgeManager.updateSubmission(judge);
            return CommonResult.successResponse();
        } catch (StatusFailException e) {
            return CommonResult.errorResponse(e.getMessage());
        } catch (StatusForbiddenException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.FORBIDDEN);
        }
    }

    @Override
    public CommonResult<HashMap<Long, Object>> checkCommonJudgeResult(SubmitIdListDTO submitIdListDto) {
        return CommonResult.successResponse(judgeManager.checkCommonJudgeResult(submitIdListDto));
    }

    @Override
    public CommonResult<HashMap<Long, Object>> checkContestJudgeResult(SubmitIdListDTO submitIdListDto) {
        try {
            return CommonResult.successResponse(judgeManager.checkContestJudgeResult(submitIdListDto));
        } catch (StatusNotFoundException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.NOT_FOUND);
        }
    }

    @Override
    public CommonResult<JudgeCaseVO> getALLCaseResult(Long submitId) {
        try {
            return CommonResult.successResponse(judgeManager.getALLCaseResult(submitId));
        } catch (StatusNotFoundException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.NOT_FOUND);
        } catch (StatusForbiddenException e) {
            return CommonResult.errorResponse(e.getMessage(), ResultStatus.FORBIDDEN);
        }
    }
}