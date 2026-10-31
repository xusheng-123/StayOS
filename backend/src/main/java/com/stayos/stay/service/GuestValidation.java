package com.stayos.stay.service;

import com.stayos.common.ApiException;

/** Same registration field rules for check-in and controlled correction. */
final class GuestValidation {
    private GuestValidation() {}
    static void require(String name,String type,String number,String phone) {
        if (name.isEmpty() || name.length()>100 || name.chars().anyMatch(Character::isISOControl)) throw invalid("请填写有效的住客姓名");
        boolean valid=switch(type) {
            case "ID_CARD"->number.matches("[0-9]{17}[0-9X]");
            case "PASSPORT"->number.matches("[A-Z0-9]{3,30}");
            case "OTHER"->number.matches("[A-Z0-9_/-]{3,64}");
            default->false;
        };
        if (!valid) throw invalid("证件类型或号码格式不正确；格式检查不代表身份核验");
        if (phone.length()>30 || (!phone.isEmpty() && !phone.matches("[+0-9() -]{3,30}"))) throw invalid("联系电话格式不正确");
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
}
