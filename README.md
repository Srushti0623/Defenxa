# Fitness AI

A personalized AI fitness trainer that generates workouts tailored to your goals, equipment, and training history. Built with Streamlit and powered by Claude.

## Why This App?

Generic chatbots forget your equipment, lose track of your progress, and don't know what day it is. This app solves that by:

- **Remembering your context** - Goals, equipment, injuries, and preferences are always available
- **Tracking your history** - Every workout is logged and used to inform future sessions
- **Knowing the date** - Workouts are generated with awareness of your training schedule
- **Structured workflows** - Purpose-built UI for generating, logging, and reviewing workouts

## Workflow

### 1. Set Up Your Profile

Go to **Profile** and enter your fitness goals, available equipment, any injuries, and training preferences. This context is sent with every workout generation request.

### 2. Create a Training Plan

Go to **Plans** and create one or more training plans. A plan might be "Strength Training" with a description of your split (e.g., Lower/Upper/Full Body rotation). You can have multiple active plans.

### 3. Generate a Workout

On the **Generate Workout** page:
1. Select your plan
2. Optionally add context ("feeling tired", "want to focus on back")
3. Click Generate - the AI creates a workout based on your profile, plan, and recent history
4. Review and edit exercises if needed
5. Queue the workout for logging later

### 4. Log Your Workout

On the **Log Workout** page:
1. See your queued workout with planned exercises
2. After training, enter what you actually did in freeform text (e.g., "DL 3x5 @ 155, skipped lunges")
3. The AI parses your notes into structured exercise entries
4. Add duration and any notes
5. Save to complete the workout

### 5. Review History

The **History** page shows all past workouts with filtering by date, status, and plan. You can also import workouts from text if you have historical training data.

## Getting Started

### Prerequisites

- Python 3.9+
- [uv](https://github.com/astral-sh/uv) package manager
- Anthropic API key

### Installation

1. Clone the repository:
   ```bash
   git clone git@github.com:AdamSomers/fitness-app.git
   cd fitness-app
   ```

2. Create and activate a virtual environment:
   ```bash
   uv venv
   source .venv/bin/activate  # On macOS/Linux
   # or: .venv\Scripts\activate  # On Windows
   ```

3. Install dependencies:
   ```bash
   uv pip install -r requirements.txt
   ```

4. Create a `.env` file with your Anthropic API key:
   ```
   ANTHROPIC_API_KEY=your_key_here
   ```

5. Start the app:
   ```bash
   uv run streamlit run app.py
   ```

The app will be available at http://localhost:8501

### First Run

1. Go to **Profile** and fill in your details
2. Go to **Plans** and create at least one training plan
3. Go to **Generate Workout** to create your first AI-generated workout

### Seeding Test Data

To populate the app with sample data for testing:
```bash
uv run python seed.py          # Add sample data
uv run python seed.py --reset  # Clear DB and reseed
```

---

## Developer Features

### Dev Tools Page

The **Dev Tools** page provides utilities for development and data management:

#### Data Export/Import

Export all app data (profile, plans, workouts) as JSON for backup or migration to another instance. Import restores data with conflict detection - if importing data that already exists, you can choose to overwrite or skip each conflicting item.

This is useful for:
- Backing up your training data
- Migrating to a new machine
- Sharing training history

#### Plan Rollout Simulation

Preview how a training plan evolves over time by generating a sequence of workouts. Each workout is generated with the context of previous workouts as if they were completed. This helps you:
- Validate that your plan description produces good workout variety
- See how the AI progresses exercises over time
- Test changes to your plan before committing

### Running Tests

```bash
uv run pytest           # Run all tests
uv run pytest -v        # Verbose output
uv run pytest tests/test_parsers.py  # Run specific file
```

### Project Structure

```
fitness-app/
├── app.py                 # Main Streamlit entry point
├── pages/                 # Streamlit pages
│   ├── 1_Generate_Workout.py
│   ├── 2_Log_Workout.py
│   ├── 3_History.py
│   ├── 4_Profile.py
│   ├── 5_Plans.py
│   └── 6_Dev_Tools.py
├── services/              # Business logic
├── database/              # SQLite repositories
├── llm/                   # Claude API integration
├── models/                # Domain models
└── tests/                 # Test suite
```
