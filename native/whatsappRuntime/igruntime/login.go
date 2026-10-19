package igruntime

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"sync"
)

const (
	LoginStateCredentials   = "credentials"
	LoginStateTwoFactor     = "2fa"
	LoginStateWebChallenge  = "web_challenge"
	LoginStateCaptcha       = "captcha"
	LoginStateAuthenticated = "authenticated"
	LoginStateCancelled     = "cancelled"
	LoginStateFailed        = "failed"

	LoginStepCredentials  = "fi.mau.meta.instagram.credentials"
	LoginStepWebTwoFactor = "fi.mau.meta.instagram.web_two_factor"
	LoginStepWebChallenge = "fi.mau.meta.instagram.web_challenge"

	loginFailureTransportUnavailable = "login_transport_unavailable"
	loginFailureInvalidStep          = "invalid_upstream_step"
	loginFailureInvalidWebURL        = "invalid_webview_url"
	loginWaitForInstagramURL         = `^https://www\.instagram\.com/(?:direct/(?:inbox/|t/[0-9]+/)?)?(?:\?.*)?$`
)

// LoginMachine is the narrow, gomobile-safe boundary around the pinned
// mautrix-meta/igconnector login flow. It carries step metadata only. It does
// not run credentials, cookies, CAPTCHA tokens, or challenge payloads through
// ordinary UI state.
type LoginMachine struct {
	mu             sync.Mutex
	state          string
	stepID         string
	instructions   string
	fieldIDs       []string
	webViewURL     string
	webViewUA      string
	waitForURL     string
	failureCode    string
	webViewPending bool
}

type loginSnapshot struct {
	State        string   `json:"state"`
	StepID       string   `json:"step_id,omitempty"`
	Instructions string   `json:"instructions,omitempty"`
	FieldIDs     []string `json:"field_ids,omitempty"`
	FailureCode  string   `json:"failure_code,omitempty"`
}

// NewLoginMachine starts at the exact upstream credentials step. No device,
// cookie, browser, or network operation occurs here.
func NewLoginMachine() *LoginMachine {
	return &LoginMachine{
		state:    LoginStateCredentials,
		stepID:   LoginStepCredentials,
		fieldIDs: []string{"username", "password"},
	}
}

func (m *LoginMachine) State() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.state
}

// SnapshotJSON is safe for ordinary UI state. It intentionally omits any
// WebView URL, user-agent, cookie name/value, token, or credential.
func (m *LoginMachine) SnapshotJSON() []byte {
	m.mu.Lock()
	defer m.mu.Unlock()
	value, _ := json.Marshal(loginSnapshot{
		State:        m.state,
		StepID:       m.stepID,
		Instructions: m.instructions,
		FieldIDs:     append([]string(nil), m.fieldIDs...),
		FailureCode:  m.failureCode,
	})
	return value
}

// WebViewPending reports whether a dedicated WebView controller is required.
func (m *LoginMachine) WebViewPending() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.webViewPending
}

// WebViewURL returns the temporary URL only to the dedicated WebView
// controller. It is empty for non-WebView states.
func (m *LoginMachine) WebViewURL() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.webViewURL
}

func (m *LoginMachine) WebViewUserAgent() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.webViewUA
}

func (m *LoginMachine) WebViewWaitPattern() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.waitForURL
}

// ApplyUpstreamStep maps a sanitized bridgev2 LoginStep into this facade. The
// real connector is responsible for obtaining the step. This method never
// accepts a password or raw LoginStep JSON.
func (m *LoginMachine) ApplyUpstreamStep(state, stepID, instructions string, fieldIDs []string, webViewURL, userAgent string) error {
	if !validLoginState(state) || strings.TrimSpace(stepID) == "" {
		m.fail(loginFailureInvalidStep)
		return errors.New(loginFailureInvalidStep)
	}
	if state == LoginStateWebChallenge || state == LoginStateCaptcha {
		if err := validateLoginWebURL(webViewURL); err != nil {
			m.fail(loginFailureInvalidWebURL)
			return err
		}
	}
	if state == LoginStateAuthenticated {
		fieldIDs = nil
		webViewURL = ""
		userAgent = ""
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.state == LoginStateCancelled || m.state == LoginStateAuthenticated || m.state == LoginStateFailed {
		return errors.New("Instagram login machine is terminal")
	}
	m.state = state
	m.stepID = stepID
	m.instructions = instructions
	m.fieldIDs = sanitizeFieldIDs(fieldIDs)
	m.webViewURL = webViewURL
	m.webViewUA = userAgent
	m.waitForURL = ""
	m.webViewPending = state == LoginStateWebChallenge || state == LoginStateCaptcha
	if m.webViewPending {
		m.waitForURL = loginWaitForInstagramURL
	}
	return nil
}

// SubmitCredentials accepts the values only for the duration of this call and
// then discards them. The pinned upstream transport has not been connected to
// this facade, so this deliberately fails instead of pretending to authenticate.
func (m *LoginMachine) SubmitCredentials(username, password string) error {
	if strings.TrimSpace(username) == "" || password == "" {
		return errors.New("username and password are required")
	}
	return m.unavailableAfterSecret()
}

// SubmitTwoFactor has the same non-persistence guarantee as SubmitCredentials.
func (m *LoginMachine) SubmitTwoFactor(code string) error {
	if strings.TrimSpace(code) == "" {
		return errors.New("two-factor code is required")
	}
	return m.unavailableAfterSecret()
}

// SubmitCaptchaResult reports only completion. The CAPTCHA token itself stays
// in the dedicated WebView/connector handoff and never enters this object.
func (m *LoginMachine) SubmitCaptchaResult(completed bool) error {
	if !completed {
		return errors.New("CAPTCHA was not completed")
	}
	return m.unavailableAfterSecret()
}

func (m *LoginMachine) Cancel() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.state = LoginStateCancelled
	m.stepID = ""
	m.instructions = ""
	m.fieldIDs = nil
	m.webViewURL = ""
	m.webViewUA = ""
	m.waitForURL = ""
	m.webViewPending = false
}

func (m *LoginMachine) unavailableAfterSecret() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.state == LoginStateCancelled || m.state == LoginStateAuthenticated {
		return errors.New("Instagram login machine is terminal")
	}
	m.state = LoginStateFailed
	m.stepID = ""
	m.instructions = ""
	m.fieldIDs = nil
	m.failureCode = loginFailureTransportUnavailable
	m.webViewURL = ""
	m.webViewUA = ""
	m.waitForURL = ""
	m.webViewPending = false
	return errors.New(loginFailureTransportUnavailable)
}

func (m *LoginMachine) fail(code string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.state = LoginStateFailed
	m.failureCode = code
	m.webViewURL = ""
	m.webViewUA = ""
	m.waitForURL = ""
	m.webViewPending = false
}

func validLoginState(state string) bool {
	switch state {
	case LoginStateCredentials, LoginStateTwoFactor, LoginStateWebChallenge, LoginStateCaptcha, LoginStateAuthenticated, LoginStateCancelled, LoginStateFailed:
		return true
	default:
		return false
	}
}

func sanitizeFieldIDs(fieldIDs []string) []string {
	result := make([]string, 0, len(fieldIDs))
	for _, fieldID := range fieldIDs {
		switch fieldID {
		case "username", "password", "two_factor_code", "select", "captcha_completion":
			result = append(result, fieldID)
		}
	}
	return result
}

func validateLoginWebURL(raw string) error {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.User != nil {
		return errors.New(loginFailureInvalidWebURL)
	}
	host := strings.ToLower(parsed.Hostname())
	if host != "www.instagram.com" && host != "instagram.com" {
		return fmt.Errorf("%s: host is not Instagram", loginFailureInvalidWebURL)
	}
	return nil
}
