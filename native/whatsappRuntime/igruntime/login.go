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
	LoginStateSelect        = "select"
	LoginStateWebChallenge  = "web_challenge"
	LoginStateCaptcha       = "captcha"
	LoginStateDisplayWait   = "display_wait"
	LoginStateAuthenticated = "authenticated"
	LoginStateCancelled     = "cancelled"
	LoginStateFailed        = "failed"

	// Pinned upstream step IDs (go.mau.fi/mautrix-meta/pkg/igconnector and
	// pkg/instameow at v0.2610.0). They are carried through verbatim.
	LoginStepCredentials  = "fi.mau.meta.instagram.credentials"
	LoginStepWebTwoFactor = "fi.mau.meta.instagram.web_two_factor"
	LoginStepWebChallenge = "fi.mau.meta.instagram.web_challenge"
	LoginStepWebCaptcha   = "fi.mau.meta.instagram.web_captcha"

	loginFailureInvalidStep   = "invalid_upstream_step"
	loginFailureInvalidWebURL = "invalid_webview_url"

	// loginWaitForInstagramURL mirrors upstream instagramWebLoggedInURLPattern.
	loginWaitForInstagramURL = `^https://www\.instagram\.com/(?:direct/(?:inbox/|t/[0-9]+/)?)?(?:\?.*)?$`
)

// UpstreamStep is a sanitized projection of a pinned bridgev2.LoginStep. It
// carries step metadata only. WebView material is kept separate from the
// ordinary-UI snapshot so a URL, user-agent, token, cookie, or extraction
// script never reaches saved state or logs.
type UpstreamStep struct {
	State              string
	StepID             string
	Instructions       string
	FieldIDs           []string
	Choices            []LoginChoice
	WebViewURL         string
	WebViewUserAgent   string
	WebViewExtractJS   string
	WebViewWaitPattern string
}

// LoginChoice preserves the upstream submission value and display label.
// The pinned bridge currently supplies the same string for both.
type LoginChoice struct {
	FieldID string `json:"field_id"`
	Value   string `json:"value"`
	Label   string `json:"label"`
}

// LoginMachine is the narrow, gomobile-safe boundary around the pinned
// mautrix-meta login flow. It never runs credentials, cookies, CAPTCHA tokens,
// or challenge payloads through ordinary UI state.
type LoginMachine struct {
	mu             sync.Mutex
	state          string
	stepID         string
	instructions   string
	fieldIDs       []string
	choices        []LoginChoice
	webViewURL     string
	webViewUA      string
	webViewExtract string
	waitForURL     string
	failureCode    string
	webViewPending bool
}

type loginSnapshot struct {
	State        string        `json:"state"`
	StepID       string        `json:"step_id,omitempty"`
	Instructions string        `json:"instructions,omitempty"`
	FieldIDs     []string      `json:"field_ids,omitempty"`
	Choices      []LoginChoice `json:"choices,omitempty"`
	WebViewStep  bool          `json:"webview_step,omitempty"`
	FailureCode  string        `json:"failure_code,omitempty"`
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

func (m *LoginMachine) StepID() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.stepID
}

// SnapshotJSON is safe for ordinary UI state. It intentionally omits any
// WebView URL, user-agent, extraction script, cookie name/value, token, or
// credential.
func (m *LoginMachine) SnapshotJSON() []byte {
	m.mu.Lock()
	defer m.mu.Unlock()
	value, _ := json.Marshal(loginSnapshot{
		State:        m.state,
		StepID:       m.stepID,
		Instructions: m.instructions,
		FieldIDs:     append([]string(nil), m.fieldIDs...),
		Choices:      append([]LoginChoice(nil), m.choices...),
		WebViewStep:  m.webViewPending,
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

// WebViewExtractJS returns the pinned upstream extraction script for a CAPTCHA
// step. It is handed only to the dedicated WebView controller.
func (m *LoginMachine) WebViewExtractJS() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.webViewExtract
}

func (m *LoginMachine) WebViewWaitPattern() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.waitForURL
}

// Apply maps a sanitized upstream step into this facade. The caller (the login
// controller) is responsible for obtaining the step from the real connector.
// This method never accepts a password or raw LoginStep JSON.
func (m *LoginMachine) Apply(step UpstreamStep) error {
	if !validLoginState(step.State) || strings.TrimSpace(step.StepID) == "" && step.State != LoginStateAuthenticated {
		m.fail(loginFailureInvalidStep)
		return errors.New(loginFailureInvalidStep)
	}
	webView := step.State == LoginStateWebChallenge || step.State == LoginStateCaptcha
	if webView {
		if err := validateLoginWebURL(step.WebViewURL); err != nil {
			m.fail(loginFailureInvalidWebURL)
			return err
		}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.state == LoginStateCancelled || m.state == LoginStateAuthenticated || m.state == LoginStateFailed {
		return errors.New("Instagram login machine is terminal")
	}
	m.state = step.State
	m.stepID = step.StepID
	m.instructions = step.Instructions
	m.fieldIDs = sanitizeFieldIDs(step.FieldIDs)
	m.choices = sanitizeChoices(step.Choices)
	m.webViewPending = webView
	if webView {
		m.webViewURL = step.WebViewURL
		m.webViewUA = step.WebViewUserAgent
		m.webViewExtract = step.WebViewExtractJS
		m.waitForURL = step.WebViewWaitPattern
		if m.waitForURL == "" {
			m.waitForURL = loginWaitForInstagramURL
		}
	} else {
		m.webViewURL = ""
		m.webViewUA = ""
		m.webViewExtract = ""
		m.waitForURL = ""
	}
	if step.State == LoginStateAuthenticated {
		m.fieldIDs = nil
		m.instructions = ""
	}
	return nil
}

// ApplyUpstreamStep is the narrow positional form retained for callers and
// tests that predate the UpstreamStep struct.
func (m *LoginMachine) ApplyUpstreamStep(state, stepID, instructions string, fieldIDs []string, webViewURL, userAgent string) error {
	return m.Apply(UpstreamStep{
		State:            state,
		StepID:           stepID,
		Instructions:     instructions,
		FieldIDs:         fieldIDs,
		WebViewURL:       webViewURL,
		WebViewUserAgent: userAgent,
	})
}

// markFailed records a fixed, secret-free failure code. The underlying error
// text from upstream is never stored here.
func (m *LoginMachine) markFailed(code string) {
	m.fail(code)
}

func (m *LoginMachine) Cancel() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.state = LoginStateCancelled
	m.stepID = ""
	m.instructions = ""
	m.fieldIDs = nil
	m.choices = nil
	m.webViewURL = ""
	m.webViewUA = ""
	m.webViewExtract = ""
	m.waitForURL = ""
	m.webViewPending = false
}

func (m *LoginMachine) fail(code string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.state == LoginStateCancelled || m.state == LoginStateAuthenticated {
		return
	}
	m.state = LoginStateFailed
	m.failureCode = code
	m.stepID = ""
	m.instructions = ""
	m.fieldIDs = nil
	m.choices = nil
	m.webViewURL = ""
	m.webViewUA = ""
	m.webViewExtract = ""
	m.waitForURL = ""
	m.webViewPending = false
}

func validLoginState(state string) bool {
	switch state {
	case LoginStateCredentials, LoginStateTwoFactor, LoginStateSelect, LoginStateWebChallenge,
		LoginStateCaptcha, LoginStateDisplayWait, LoginStateAuthenticated, LoginStateCancelled, LoginStateFailed:
		return true
	default:
		return false
	}
}

// allowedFieldIDs are the ordinary-UI input identifiers upstream emits for
// non-WebView steps. These are field labels, not values: a password value is
// never stored or logged, but the "password" field ID tells the screen to show
// a password input. Token-carrier fields (captcha_token, recaptcha_token) are
// intentionally absent because their values flow only through the dedicated
// WebView handoff, never ordinary UI state.
var allowedFieldIDs = map[string]struct{}{
	"username":          {},
	"password":          {},
	"verification_code": {},
	"two_factor_code":   {},
	"account":           {},
	"select":            {},
	"retry":             {},
	"captcha_code":      {},
}

func sanitizeFieldIDs(fieldIDs []string) []string {
	result := make([]string, 0, len(fieldIDs))
	for _, fieldID := range fieldIDs {
		if _, ok := allowedFieldIDs[fieldID]; ok {
			result = append(result, fieldID)
		}
	}
	if len(result) == 0 {
		return nil
	}
	return result
}

func sanitizeChoices(choices []LoginChoice) []LoginChoice {
	result := make([]LoginChoice, 0, len(choices))
	for _, choice := range choices {
		if strings.TrimSpace(choice.FieldID) == "" || choice.Value == "" || choice.Label == "" {
			continue
		}
		result = append(result, choice)
	}
	if len(result) == 0 {
		return nil
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
