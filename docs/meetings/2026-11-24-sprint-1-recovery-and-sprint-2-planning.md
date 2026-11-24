# Partay meeting 24 November 2026: Sprint 1 recovery and Sprint 2 planning

## Meeting details

- Date: Tuesday 24 November 2026, from 9:30
- Place: VIA University College, Horsens
- Attendees: Alexandru Turcanu (Scrum Master), Nelson Rodrigo Sardinha Silva (Product Owner), Kristína Sopková, Ilja Zaicevs (joined late)
- Notes by: Alexandru Turcanu

## Sprint 1 outcome (17 to 23 November)

- No sprint planning and no meeting took place in Sprint 1.
- Reason: every member was busy and the team was disorganized that week.
- Result: 0 of 13 tasks done, 0 of 98 planned hours spent, no task had an owner.
- The proof of concept from the inception phase already covers part of the Sprint 1 backend: MySQL schema, gRPC Data Server, create party, join, Spotify search, add song and voting.

## Decisions

1. The inception document is the source of truth. The proof of concept changes to match it, not the other way round. The inception document stays as it is. Deviations go in the process report.
2. Six changes bring the proof of concept in line with the inception document. The team accepted all six:
    1. Voting follows UC4: one vote per guest per song, a second vote takes it back, no downvotes.
    2. Redis comes back as the cache for the live queue, rebuilt from MySQL when the Application Server starts.
    3. The code follows the domain model, or the report explains the mapping to the queue_item table.
    4. The party rule fields and the guest anonymous flag go into the schema in Sprint 2.
    5. The host logs in with Spotify (PKCE). Client credentials stay for search.
    6. The Blazor Web App replaces the demo page and the PowerShell scripts.
3. New sprint plan:
    - Sprint 2, 24 to 30 November: Figma mockups, Blazor skeleton, Spotify login, party playlist, create, join, search and add (US1 to US3).
    - Sprint 3, 1 to 7 December: voting, live updates, playlist in vote order, now playing, remove song (US4 to US7).
    - Sprint 4, 8 to 11 December: tests, defect fixes, additional features (US8 to US14) as stretch goals.
    - Final week, 12 to 18 December: reports and presentation. Hand-in 18 December at 13:00.
4. The team reorganised the tasks in the tracker at alextudev.com/partay:
    - Sprint 1 stays unchanged as a record of the missed sprint.
    - The old Sprint 2 tasks (voting, live updates, reorder, remove) moved to Sprint 3.
    - Sprint 2 holds 17 tasks and 99 hours. Every task has one owner.
    - The product backlog follows the new sprints. It has no names on it, because the Product Owner owns it.
5. Sprint 2 owners, as in the tracker and the GitHub issues:
    - Alex, 30 h: team repository, sprint events, process notes, Spotify login, party playlist.
    - Ilja, 24 h: Data Server schema and tests, App Server REST changes, Redis.
    - Kristína, 23 h: Figma guest screens, Blazor skeleton, guest pages, Spotify playback test.
    - Rodrigo, 22 h: Figma host screens, host pages, backlog upkeep, report sections.
6. Design: Kristína and Rodrigo started the brand identity during the meeting, then each works on their own screens. Direction: a glass look over dark gradients, and a party vibe the host picks (US13) that sets the background colours. Backgrounds based on the genres in the queue are a Sprint 4 stretch idea. The Figma design is not final yet.
7. Repository: the proof of concept repository became the team repository, a1izo/Partay. Main is protected: pull request with 1 approval, all conversations resolved, no bypass for admins. All Sprint 2 tasks are GitHub issues with the tracker ID in the title.
8. Spotify: the Partay developer app runs on Alex's Spotify Premium account. Alex is the only tester, so no other users are added to the app.
9. Communication: the team checks up with each other through text messages. There is no fixed stand-up.

## Retrospective

No retrospective took place. The meeting focused on reorganising the tasks. The retrospective for Sprint 1 and Sprint 2 moves to the next meeting.

## Action items

- Ilja and Rodrigo: accept the GitHub invite to a1izo/Partay.
- Alex: assign Ilja's and Rodrigo's issues once they accept.
- Alex: tag the proof of concept as poc-v1 and start S2-15, Spotify login.
- Kristína and Rodrigo: finish the Figma mockups before the Blazor pages start.
- Alex: book a supervisor meeting for the week of 30 November.

## Next meeting

Tuesday 1 December 2026, 9:30 to 12:00: Sprint 2 review, retrospective for Sprint 1 and Sprint 2, Sprint 3 planning.
