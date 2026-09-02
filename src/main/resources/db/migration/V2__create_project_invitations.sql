-- ---------------------------------------------------------------------------
-- project_invitations
-- ---------------------------------------------------------------------------
CREATE TABLE project_invitations (
  id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  project_id  VARCHAR(8)   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  email       VARCHAR(255) NOT NULL,
  role        project_role NOT NULL DEFAULT 'member',
  token_hash  VARCHAR(64)  NOT NULL,
  invited_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
  expires_at  TIMESTAMPTZ  NOT NULL,
  accepted_at TIMESTAMPTZ,
  accepted_by UUID,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_project_invitations_token_hash ON project_invitations (token_hash);
CREATE INDEX idx_project_invitations_project_id ON project_invitations (project_id);
CREATE INDEX idx_project_invitations_email ON project_invitations (email);
