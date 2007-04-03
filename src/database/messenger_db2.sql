--   $RCSfile$   
--   $Revision$
--   $Date$

CREATE TABLE jiveUser (
  username              VARCHAR(32)     NOT NULL,
  password              VARCHAR(32)     NOT NULL,
  name                  VARCHAR(100),
  email                 VARCHAR(100),
  creationDate          CHAR(15)        NOT NULL,
  modificationDate      CHAR(15)        NOT NULL,
  CONSTRAINT jiveUser_pk PRIMARY KEY (userID)
);
CREATE INDEX jiveUsr_cDate_idx ON jiveUser (creationDate ASC);


CREATE TABLE jiveUserProp (
  username              VARCHAR(32)     NOT NULL,
  name                  VARCHAR(100)    NOT NULL,
  propValue             VARCHAR(2000)   NOT NULL,
  CONSTRAINT jiveUsrProp_pk PRIMARY KEY (username, name)
);


CREATE TABLE jivePrivate (
  username              VARCHAR(32)     NOT NULL,
  name                  VARCHAR(100)    NOT NULL,
  namespace             VARCHAR(200)    NOT NULL,
  value                 LONG VARCHAR    NOT NULL,
  CONSTRAINT jivePrivate_pk PRIMARY KEY (username, name, namespace)
);


CREATE TABLE jiveOffline (
  username              VARCHAR(32)     NOT NULL,
  messageID             INTEGER         NOT NULL,
  creationDate          CHAR(15)        NOT NULL,
  messageSize           INTEGER         NOT NULL,
  message               LONG VARCHAR    NOT NULL,
  CONSTRAINT jiveOffline_pk PRIMARY KEY (username, messageID)
);


CREATE TABLE jiveRoster (
  rosterID              INTEGER         NOT NULL,
  username              VARCHAR(32)     NOT NULL,
  jid                   VARCHAR(1024)   NOT NULL,
  sub                   INTEGER         NOT NULL,
  ask                   INTEGER         NOT NULL,
  recv                  INTEGER         NOT NULL,
  nick                  VARCHAR(255),
  CONSTRAINT jiveRoster_pk PRIMARY KEY (rosterID)
);
CREATE INDEX jiveR_userid_idx ON jiveRoster (userID ASC);


CREATE TABLE jiveRosterGroups (
  rosterID              INTEGER         NOT NULL,
  rank                  INTEGER         NOT NULL,
  groupName             VARCHAR(255)    NOT NULL,
  CONSTRAINT jiveRoGrps_pk PRIMARY KEY (rosterID, rank)
);
CREATE INDEX jiveRoGrps_rid_idx ON jiveRosterGroups (rosterID ASC);


CREATE TABLE jiveVCard (
  username              VARCHAR(32)     NOT NULL,
  name                  VARCHAR(100)    NOT NULL,
  propValue             VARCHAR(2000)   NOT NULL,
  CONSTRAINT jiveVCard_pk PRIMARY KEY (username, name)
);


CREATE TABLE jiveGroup (
  groupID               INTEGER         NOT NULL,
  name                  VARCHAR(50)     NOT NULL,
  description           VARCHAR(255),
  creationDate          CHAR(15)        NOT NULL,
  modificationDate      CHAR(15)        NOT NULL,
  CONSTRAINT jiveGroup_pk PRIMARY KEY (groupID)
);
CREATE INDEX jiveGrp_cDate_idx ON jiveGroup (creationDate ASC);
CREATE INDEX jiveGrp_name_idx ON jiveGroup (name);


CREATE TABLE jiveGroupProp (
  groupID               INTEGER         NOT NULL,
  name                  VARCHAR(100)    NOT NULL,
  propValue             VARCHAR(2000)   NOT NULL,
  CONSTRAINT jiveGrpProp_pk PRIMARY KEY (groupID, name)
);


CREATE TABLE jiveGroupUser (
  groupID               INTEGER         NOT NULL,
  username              VARCHAR(32)     NOT NULL,
  administrator         INTEGER         NOT NULL,
  CONSTRAINT jiveGrpUser PRIMARY KEY (groupID, userID, administrator)
);

 
CREATE TABLE jiveID (
  idType                INTEGER         NOT NULL,
  id                    INTEGER         NOT NULL,
  CONSTRAINT jiveID_pk PRIMARY KEY (idType)
);


CREATE TABLE jiveProperty (
  name        VARCHAR(100) NOT NULL,
  propValue   VARCHAR(3000) NOT NULL,
  CONSTRAINT jiveProperty_pk PRIMARY KEY (name)
);

-- MUC tables

CREATE TABLE mucRoom (
  roomID              INTEGER       NOT NULL,
  creationDate        CHAR(15)      NOT NULL,
  modificationDate    CHAR(15)      NOT NULL,
  name                VARCHAR(50)   NOT NULL,
  naturalName         VARCHAR(255)  NOT NULL,
  description         VARCHAR(255),
  canChangeSubject    INTEGER       NOT NULL,
  maxUsers            INTEGER       NOT NULL,
  publicRoom          INTEGER       NOT NULL,
  moderated           INTEGER       NOT NULL,
  invitationRequired  INTEGER       NOT NULL,
  canInvite           INTEGER       NOT NULL,
  password            VARCHAR(50)   NULL,
  canDiscoverJID      INTEGER       NOT NULL,
  logEnabled          INTEGER       NOT NULL,
  subject             VARCHAR(100)  NULL,
  rolesToBroadcast    INTEGER       NOT NULL,
  lastActiveDate      CHAR(15)      NULL,
  inMemory            INTEGER       NOT NULL,
  CONSTRAINT mucRoom_pk PRIMARY KEY (name)
);

CREATE INDEX mucRoom_roomid_idx ON mucRoom (roomID);

CREATE TABLE mucAffiliation (
  roomID              INTEGER       NOT NULL,
  jid                 VARCHAR(1024) NOT NULL,
  affiliation         INTEGER       NOT NULL,
  CONSTRAINT mucAffiliation_pk PRIMARY KEY (roomID, jid)
);

CREATE TABLE mucMember (
  roomID              INTEGER       NOT NULL,
  jid                 VARCHAR(1024) NOT NULL,
  nickname            VARCHAR(255)  NULL,
  CONSTRAINT mucMember_pk PRIMARY KEY (roomID, jid)
);

CREATE TABLE mucConversationLog (
  roomID              INTEGER       NOT NULL,
  sender              VARCHAR(1024) NOT NULL,
  nickname            VARCHAR(255)  NULL,
  time                CHAR(15)      NOT NULL,
  subject             VARCHAR(255)  NULL,
  body                LONG VARCHAR  NULL
);


-- Finally, insert default table values
INSERT INTO jiveID (idType, id) VALUES (3, 2);
INSERT INTO jiveID (idType, id) VALUES (4, 1);
INSERT INTO jiveID (idType, id) VALUES (18, 1);
INSERT INTO jiveID (idType, id) VALUES (19, 1);
INSERT INTO jiveID (idType, id) VALUES (23, 1);

-- Entry for admin user
INSERT INTO jiveUser (username, password, name, email, creationDate, modificationDate)
    VALUES ('admin', 'admin', 'Administrator', 'admin@example.com', '0', '0');