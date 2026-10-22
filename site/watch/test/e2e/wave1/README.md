Wave 1 web end-to-end checks (python playwright).

    cd site/watch/test/e2e/wave1
    openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 30 -subj /CN=localhost
    node mock-addon.js &        # https://localhost:4180 (self-signed)
    node mock-sync.js &         # http://localhost:4181 serves /watch plus a stub account API
    python3 w1_ui.py && python3 w1_player.py

Screenshots land in ./shots (override with SHOTS=). key.pem, cert.pem and shots/ are not committed.
