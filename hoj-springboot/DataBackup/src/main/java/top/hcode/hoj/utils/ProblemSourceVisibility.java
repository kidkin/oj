package top.hcode.hoj.utils;

import cn.hutool.core.bean.BeanUtil;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.pojo.vo.ProblemInfoVO;
import top.hcode.hoj.pojo.vo.ProblemVO;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Applies the metadata rules for public problem responses. */
public final class ProblemSourceVisibility {
    private ProblemSourceVisibility() {}

    private static final Pattern DIFFICULTY_PREFIX = Pattern.compile(
            "^(?:[\\s　]*[【\\[](?:入门|普及(?:[+＋−－-])?(?:[/／]提高[−－-])?|提高(?:[+＋−－-])?(?:[/／]省选[−－-])?|省选[/／]NOI[−－-]|NOI[/／]NOI[+＋][/／]CTS)[】\\]])+[\\s　]*");

    private static boolean canSeeSource(Subject viewer) {
        return viewer.hasRole("root") || viewer.hasRole("admin") || viewer.hasRole("problem_admin");
    }

    public static boolean canSeeMetadata() {
        Subject viewer = SecurityUtils.getSubject();
        return canSeeSource(viewer) || viewer.hasRole("teacher");
    }

    public static String cleanTitle(String title) {
        if (title == null) return null;
        String clean = DIFFICULTY_PREFIX.matcher(title).replaceFirst("");
        return clean.isEmpty() ? title : clean;
    }

    public static ProblemInfoVO details(ProblemInfoVO info) {
        Subject viewer = SecurityUtils.getSubject();
        boolean metadata = canSeeSource(viewer) || viewer.hasRole("teacher");
        Problem problem = BeanUtil.copyProperties(forViewer(info.getProblem()), Problem.class);
        problem.setTitle(cleanTitle(problem.getTitle()));
        if (!metadata) {
            problem.setAuthor(null);
            problem.setDifficulty(null);
            info.setTags(Collections.emptyList());
        }
        info.setProblem(problem);
        return info;
    }

    public static Problem forViewer(Problem problem) {
        if (canSeeSource(SecurityUtils.getSubject())) return problem;
        Problem result = BeanUtil.copyProperties(problem, Problem.class);
        result.setSource(null);
        return result;
    }

    public static void list(List<ProblemVO> rows) {
        boolean metadata = canSeeMetadata();
        for (ProblemVO row : rows) {
            row.setTitle(cleanTitle(row.getTitle()));
            if (!metadata) {
                row.setDifficulty(null);
                row.setTags(Collections.emptyList());
            }
        }
    }
}
