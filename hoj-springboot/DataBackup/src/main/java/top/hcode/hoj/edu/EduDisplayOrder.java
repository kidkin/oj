package top.hcode.hoj.edu;

import java.util.*;

/** Human-editable display numbers are independent of persistent entity IDs. */
final class EduDisplayOrder {
    private EduDisplayOrder() {}
    static Long parse(String value) {
        if (value == null) return null;
        if (!value.matches("[1-9][0-9]{0,9}")) throw new EduException(400, "显示ID须为1至2147483647的整数");
        long number = Long.parseLong(value); validate(number); return number;
    }
    static List<Long> parseProblems(List<String> values) {
        if (values == null) return null;
        List<Long> result = new ArrayList<>(); for (String value : values) result.add(parse(value)); return result;
    }
    static void validate(Long value) {
        if (value != null && (value < 1 || value > Integer.MAX_VALUE))
            throw new EduException(400, "显示ID须为1至2147483647的整数");
    }
    static List<Long> problems(List<Long> ids, List<Long> displayIds) {
        if (displayIds == null) {
            List<Long> defaults = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) defaults.add((long) i + 1);
            return defaults;
        }
        if (displayIds.size() != ids.size()) throw new EduException(400, "每道题须填写一个课程内显示ID");
        Set<Long> used = new HashSet<>();
        for (Long value : displayIds) {
            if (value == null) throw new EduException(400, "请填写题目的课程内显示ID");
            validate(value);
            if (!used.add(value)) throw new EduException(400, "同一课程内的题目显示ID不能重复");
        }
        return new ArrayList<>(displayIds);
    }
}
