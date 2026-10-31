# Session Tracking Protocol

Use this procedure at the end of every chat that advances this project.

1. Read the current README, book tracker, and recent session records before continuing research.
2. Generate a fresh UUID for the chat. Create research/sessions/<uuid>.md with the date, objective, sources consulted, work completed, access limits, updated statuses, and next actions.
3. Update research/BOOK_TRACKER.md when a book's research or access status changes. Keep claims about full-text reading exact.
4. Save each book's substantial research as a separate Word document in research/books/, with normal citations and source links.
5. Review all changes, commit them to main, and push to origin (https://github.com/Mscient/Intra.git).
6. Add the session UUID and the commit containing the report and session record to research/SESSION_INDEX.csv. If the index is changed after that commit, commit and push the index update as well.

Keep each session record even if the work is small. Use Git history together with the session index to monitor which research was added in each chat. Never place private credentials or broker API keys in this repository.
