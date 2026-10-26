package top.hcode.hoj.utils;

import cn.hutool.core.bean.BeanUtil;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import top.hcode.hoj.pojo.entity.problem.Problem;

/** Student responses must not contain the administrative source field. */
public final class ProblemSourceVisibility {
    private ProblemSourceVisibility() {}

    public static Problem forViewer(Problem problem) {
        Subject viewer = SecurityUtils.getSubject();
        if (viewer.hasRole("root") || viewer.hasRole("admin") || viewer.hasRole("problem_admin")) return problem;
        Problem result = BeanUtil.copyProperties(problem, Problem.class);
        result.setSource(null);
        return result;
    }
}
