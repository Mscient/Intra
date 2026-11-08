package com.mscient.intrapaper.review.g6;

import static com.mscient.intrapaper.review.g6.G6Json.require;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Calendar arithmetic only. Authentication is established by a verifier, never by a dossier boolean. */
final class G6Coverage {
    enum State { CLOSED, VERIFIED_LIVE_SESSION, UNVERIFIED_SESSION, MISSING, EXCLUDED }
    record Day(LocalDate date,State state) { }
    record Result(boolean sixCalendarMonths,boolean completeSchedule,int eligibleSessions,boolean eligibleWindow) { }

    static Result assess(LocalDate start,LocalDate end,List<Day> days,boolean calendarAuthenticated) throws IOException {
        require(end.isAfter(start),"Empty/reversed evidence interval");
        boolean six=!end.isBefore(start.plusMonths(6)),complete=true,qualified=true;
        LocalDate expected=start;
        int eligible=0,scheduled=0;
        for(Day day:days) {
            require(!day.date().isBefore(expected) && day.date().isBefore(end),"Duplicate, unordered or out-of-range calendar day");
            if(!day.date().equals(expected)) complete=false;
            expected=day.date().plusDays(1);
            if(day.state()!=State.CLOSED) scheduled++;
            if(day.state()==State.VERIFIED_LIVE_SESSION) eligible++;
            if(day.state()!=State.CLOSED && day.state()!=State.VERIFIED_LIVE_SESSION) qualified=false;
        }
        complete &= expected.equals(end);
        return new Result(six,complete,eligible,calendarAuthenticated && six && complete && qualified && scheduled>0);
    }
}
